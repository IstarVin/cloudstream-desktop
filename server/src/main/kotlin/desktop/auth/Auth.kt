package desktop.auth

import desktop.api.AuthSession
import desktop.config.Paths
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Every client, including loopback, has an independently revocable session. Raw tokens never reach disk. */
class Auth(
    private val file: File = File(Paths.dataDir, "sessions.json"),
    val worker: WorkerClient = defaultWorker(),
    val publicOrigin: String? = System.getenv("CLOUDSTREAM_PUBLIC_ORIGIN")?.takeIf { it.isNotBlank() }?.trimEnd('/'),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Session(val id: String, val hash: String, val name: String, val createdAt: Long, val expiresAt: Long, val epoch: Long)
    data class Issued(val token: String, val expiresAt: Long)
    private var sessions: List<Session> = if (file.exists()) Json.decodeFromString(file.readText()) else emptyList()
    private val epochLock = Mutex()
    @Volatile private var epoch: Long = -1
    @Volatile private var checkedAt: Long = Long.MIN_VALUE
    @Volatile private var retryAt: Long = 0

    init {
        publicOrigin?.let {
            val uri = java.net.URI(it)
            require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty()) { "CLOUDSTREAM_PUBLIC_ORIGIN must be an HTTPS origin" }
        }
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.createNewFile()
        tmp.setReadable(false, false); tmp.setWritable(false, false)
        tmp.setReadable(true, true); tmp.setWritable(true, true)
        tmp.writeText(Json.encodeToString(sessions))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    @Synchronized fun issue(name: String, epoch: Long): Issued {
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val now = clock()
        val expires = now + SESSION_SECONDS * 1000L
        sessions = sessions.filter { it.expiresAt > now }.takeLast(99) + Session(UUID.randomUUID().toString(), hash(token), name.take(100), now, expires, epoch)
        persist()
        return Issued(token, expires)
    }
    @Synchronized private fun find(token: String?): Session? {
        if (token == null || !token.matches(Regex("[a-f0-9]{64}"))) return null
        val digest = hash(token)
        return sessions.firstOrNull { it.expiresAt > clock() && MessageDigest.isEqual(it.hash.toByteArray(), digest.toByteArray()) }
    }
    fun token(call: ApplicationCall): String? = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim() ?: call.request.cookies[COOKIE]
    suspend fun isAuthorized(call: ApplicationCall): Boolean = isAuthorized(token(call))
    suspend fun isAuthorized(token: String?): Boolean {
        val session = find(token) ?: return false
        val currentEpoch = epochLock.withLock {
            val now = clock()
            val stale = checkedAt == Long.MIN_VALUE || now - checkedAt >= 60_000
            // Past the grace window (or with no verified epoch yet) a Worker outage fails closed.
            val beyondGrace = checkedAt == Long.MIN_VALUE || now - checkedAt >= OUTAGE_GRACE_SECONDS * 1000L
            if (stale && now >= retryAt) {
                try {
                    epoch = worker.request("epoch")["epoch"]!!.jsonPrimitive.long
                    checkedAt = clock()
                } catch (e: AuthFailure) {
                    // A short Worker outage must not stop local playback: keep trusting the last verified epoch.
                    if (beyondGrace) throw e
                    retryAt = now + 60_000
                }
            } else if (stale && beyondGrace) throw AuthFailure(503, "Authentication service unavailable. Try again.")
            epoch
        }
        return session.epoch == currentEpoch
    }
    suspend fun acceptEpoch(value: Long) = epochLock.withLock {
        if (value >= epoch) { epoch = value; checkedAt = clock() }
    }
    @Synchronized fun list(current: String?): List<AuthSession> {
        val currentId = find(current)?.id
        return sessions.filter { it.expiresAt > clock() && (epoch < 0 || it.epoch == epoch) }.map { AuthSession(it.id, it.name, it.createdAt, it.expiresAt, it.id == currentId) }
    }
    @Synchronized fun revoke(id: String) { sessions = sessions.filterNot { it.id == id }; persist() }
    @Synchronized fun logout(token: String?) { find(token)?.let { revoke(it.id) } }
    @Synchronized fun revokeAll() { sessions = emptyList(); persist() }
    fun isLoopback(call: ApplicationCall): Boolean = isLoopback(call.request.origin.remoteAddress)

    /**
     * The server's own mpv process fetches `/proxy/…` and subtitle URLs over loopback and holds no user session, so it
     * presents a per-process secret instead. It is never sent anywhere but 127.0.0.1 and is not valid elsewhere.
     */
    fun isInternal(call: ApplicationCall): Boolean {
        if (!isLoopback(call)) return false
        val path = call.request.path()
        val presented = call.request.headers[INTERNAL_HEADER]?.takeIf { path.startsWith("/proxy/") }
            ?: call.request.queryParameters["internal"]?.takeIf { path == "/api/subtitle" }
            ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), INTERNAL_TOKEN.toByteArray())
    }

    fun origin(call: ApplicationCall): String = publicOrigin ?: "${call.request.origin.scheme}://${call.request.headers["Host"] ?: call.request.origin.serverHost}"

    /** Browser origins are checked before cookies are accepted, including WebSocket upgrades. */
    fun sameOrigin(call: ApplicationCall): Boolean {
        if (call.request.headers["Sec-Fetch-Site"] == "cross-site") return false
        val origin = call.request.headers["Origin"] ?: return true // native clients have no Origin
        val expected = origin(call)
        return origin == expected
    }

    companion object {
        const val COOKIE = "cs_session"
        const val SESSION_SECONDS = 30 * 86400
        /** How long the last verified Worker epoch is trusted while the Worker is unreachable. */
        const val OUTAGE_GRACE_SECONDS = 24 * 3600
        const val INTERNAL_HEADER = "X-Cloudstream-Internal"
        val INTERNAL_TOKEN: String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        private val PUBLIC_API = setOf("/api/auth/status", "/api/auth/device", "/api/auth/device/poll", "/api/auth/login")
        fun isLoopback(address: String): Boolean =
            runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
        fun needsToken(path: String): Boolean = (path == "/api" || path.startsWith("/api/") || path == "/proxy" || path.startsWith("/proxy/")) && path !in PUBLIC_API
        private fun defaultWorker(): WorkerClient {
            val idFile = File(Paths.dataDir, "server-id")
            val id = if (idFile.exists()) idFile.readText().trim() else UUID.randomUUID().toString().also { idFile.writeText(it) }
            return WorkerClient(System.getenv("CLOUDSTREAM_AUTH_URL").orEmpty(), System.getenv("CLOUDSTREAM_AUTH_SECRET").orEmpty(), id,
                (System.getenv("CLOUDSTREAM_SERVER_NAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Cloudstream server")).take(100))
        }
        fun plugin(auth: Auth) = createApplicationPlugin("SessionAuth") {
            onCall { call ->
                val path = call.request.path()
                if (path.startsWith("/api/") || path.startsWith("/proxy/")) {
                    if (path.startsWith("/api/auth/")) call.response.headers.append("Cache-Control", "no-store")
                    if (!auth.sameOrigin(call)) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Open this server directly to continue"))
                        return@onCall
                    }
                    try {
                        if (needsToken(path) && !auth.isAuthorized(call) && !auth.isInternal(call)) call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in to continue"))
                    } catch (e: AuthFailure) { call.respond(HttpStatusCode.fromValue(e.status), mapOf("error" to e.message)) }
                }
            }
        }
    }
}
