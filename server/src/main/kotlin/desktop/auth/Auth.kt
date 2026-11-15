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
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Every client, including loopback, has an independently revocable session. Raw tokens never reach disk. */
class Auth(
    private val file: File = File(Paths.dataDir, "sessions.json"),
    val backend: AuthBackend = defaultBackend(),
    val publicOrigin: String? = System.getenv("CLOUDSTREAM_PUBLIC_ORIGIN")?.takeIf { it.isNotBlank() }?.trimEnd('/'),
    private val clock: () -> Long = System::currentTimeMillis,
    val serverName: String = defaultServerName(),
) {
    @Serializable
    private data class Session(val id: String, val hash: String, val name: String, val createdAt: Long, val expiresAt: Long, val epoch: Long)
    @Serializable
    private data class Stored(val mode: String, val sessions: List<Session>)
    data class Issued(val token: String, val expiresAt: Long)

    private var sessions: List<Session>
    private val epochLock = Mutex()
    @Volatile private var epoch: Long = -1
    @Volatile private var checkedAt: Long = Long.MIN_VALUE
    @Volatile private var retryAt: Long = 0
    /** sha256 of the current one-time claim code, and when it expires. */
    private var claim: Pair<String, Long>? = null
    /** Where the claim link points; [Server] sets it from the bind port. */
    @Volatile var claimBase: String = "http://127.0.0.1:6969"

    /** Present only in local mode. */
    val local: LocalBackend? get() = backend as? LocalBackend

    init {
        publicOrigin?.let {
            val uri = java.net.URI(it)
            require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty()) { "CLOUDSTREAM_PUBLIC_ORIGIN must be an HTTPS origin" }
        }
        val text = if (file.exists()) file.readText() else null
        // Files from before local mode were a bare list written by Worker mode.
        val stored = when {
            text == null -> null
            text.trimStart().startsWith("[") -> Stored("worker", Json.decodeFromString(text))
            else -> Json.decodeFromString<Stored>(text)
        }
        // A session revoked by a Worker epoch bump would come back to life in local mode, so a mode switch signs everyone out.
        val switched = stored != null && stored.mode != backend.mode
        sessions = if (switched) emptyList() else stored?.sessions.orEmpty()
        if (switched) {
            log.warn("Sign-in mode changed from {} to {}; every device must sign in again", stored!!.mode, backend.mode)
            persist()
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.createNewFile()
        tmp.setReadable(false, false); tmp.setWritable(false, false)
        tmp.setReadable(true, true); tmp.setWritable(true, true)
        tmp.writeText(Json.encodeToString(Stored(backend.mode, sessions)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    @Synchronized fun issue(name: String, epoch: Long): Issued {
        val token = Secrets.random()
        val now = clock()
        val expires = now + SESSION_SECONDS * 1000L
        sessions = sessions.filter { it.expiresAt > now }.takeLast(99) + Session(UUID.randomUUID().toString(), Secrets.sha256(token), name.take(100), now, expires, epoch)
        claim = null
        persist()
        return Issued(token, expires)
    }
    @Synchronized private fun find(token: String?): Session? {
        if (token == null || !token.matches(TOKEN)) return null
        val digest = Secrets.sha256(token)
        return sessions.firstOrNull { it.expiresAt > clock() && Secrets.equal(it.hash, digest) }
    }
    fun token(call: ApplicationCall): String? = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim() ?: call.request.cookies[COOKIE]
    suspend fun isAuthorized(call: ApplicationCall): Boolean = isAuthorized(token(call))
    suspend fun isAuthorized(token: String?): Boolean {
        val session = find(token) ?: return false
        if (!backend.usesEpoch) return true
        val currentEpoch = epochLock.withLock {
            val now = clock()
            val stale = checkedAt == Long.MIN_VALUE || now - checkedAt >= 60_000
            // Past the grace window (or with no verified epoch yet) a Worker outage fails closed.
            val beyondGrace = checkedAt == Long.MIN_VALUE || now - checkedAt >= OUTAGE_GRACE_SECONDS * 1000L
            if (stale && now >= retryAt) {
                try {
                    epoch = backend.epoch()
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
    suspend fun acceptEpoch(value: Long) {
        if (!backend.usesEpoch) return
        epochLock.withLock { if (value >= epoch) { epoch = value; checkedAt = clock() } }
    }
    @Synchronized fun list(current: String?): List<AuthSession> {
        val currentId = find(current)?.id
        return sessions.filter { it.expiresAt > clock() && (!backend.usesEpoch || epoch < 0 || it.epoch == epoch) }.map { AuthSession(it.id, it.name, it.createdAt, it.expiresAt, it.id == currentId) }
    }
    @Synchronized fun revoke(id: String) { sessions = sessions.filterNot { it.id == id }; persist(); announceClaim() }
    @Synchronized fun logout(token: String?) { find(token)?.let { revoke(it.id) } }
    @Synchronized fun revokeAll() { sessions = emptyList(); persist(); announceClaim() }
    /** After a password change: everyone but the device that made it signs in again. */
    @Synchronized fun revokeOthers(token: String?) { val keep = find(token)?.id; sessions = sessions.filter { it.id == keep }; persist() }

    /** Local mode with no password and nobody signed in: the only way in is the one-time link from the server log. */
    @Synchronized fun claimable(): Boolean = local?.passwordSet == false && sessions.none { it.expiresAt > clock() }

    /**
     * Logs a fresh one-time sign-in link when the server is [claimable]. The code goes in the URL fragment, which browsers
     * never send, so it stays out of request logs and Referer headers; the web UI posts it to `/api/auth/claim`.
     */
    @Synchronized fun announceClaim(): String? {
        if (!claimable()) return null
        val code = Secrets.random()
        claim = Secrets.sha256(code) to clock() + CLAIM_SECONDS * 1000L
        val link = "${publicOrigin ?: claimBase}/#claim=$code"
        log.warn("Nobody is signed in. Open this link within an hour to sign in (from another machine, use this server's address):\n\n    {}\n", link)
        return code
    }

    @Synchronized fun claim(code: String, name: String): Issued {
        val current = claim
        if (!claimable() || current == null || current.second <= clock() || !Secrets.equal(Secrets.sha256(code), current.first))
            throw AuthFailure(410, "This sign-in link has expired or was already used. Restart the server for a new one.")
        return issue(name, 0)
    }

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
        return Secrets.equal(presented, INTERNAL_TOKEN)
    }

    fun origin(call: ApplicationCall): String = publicOrigin ?: "${call.request.origin.scheme}://${call.request.headers["Host"] ?: call.request.origin.serverHost}"

    /** Browser origins are checked before cookies are accepted, including WebSocket upgrades. */
    fun sameOrigin(call: ApplicationCall): Boolean {
        if (isExtension(call)) return true
        if (call.request.headers["Sec-Fetch-Site"] == "cross-site") return false
        val origin = call.request.headers["Origin"] ?: return true // native clients have no Origin
        val expected = origin(call)
        return origin == expected
    }

    /**
     * The browser extension calls from its own origin. It never relies on the cookie: it signs in with the device-code flow and
     * then sends a Bearer header, which web pages can't attach cross-site without the token. So an extension origin is accepted
     * only on the device sign-in endpoints or with a Bearer header, and other cross-origin requests stay rejected.
     */
    fun isExtension(call: ApplicationCall): Boolean {
        val bearer = call.request.headers["Authorization"]?.startsWith("Bearer ") == true
        // A page can attach Authorization only in CORS mode, which always sends Origin; so a Bearer without one is never a web page.
        val origin = call.request.headers["Origin"] ?: return bearer
        if (EXTENSION_ORIGINS.none { origin.startsWith(it) }) return false
        return bearer || call.request.path() in EXTENSION_SIGN_IN
    }

    companion object {
        private val log = LoggerFactory.getLogger(Auth::class.java)
        private val TOKEN = Regex("[a-f0-9]{64}")
        private val EXTENSION_ORIGINS = listOf("chrome-extension://", "moz-extension://")
        private val EXTENSION_SIGN_IN = setOf("/api/auth/status", "/api/auth/device", "/api/auth/device/poll")
        const val COOKIE = "cs_session"
        const val SESSION_SECONDS = 30 * 86400
        const val CLAIM_SECONDS = 3600
        /** How long the last verified Worker epoch is trusted while the Worker is unreachable. */
        const val OUTAGE_GRACE_SECONDS = 24 * 3600
        const val INTERNAL_HEADER = "X-Cloudstream-Internal"
        val INTERNAL_TOKEN: String = Secrets.random()
        private val PUBLIC_API = setOf("/api/auth/status", "/api/auth/device", "/api/auth/device/poll", "/api/auth/login", "/api/auth/claim")
        fun isLoopback(address: String): Boolean =
            runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
        fun needsToken(path: String): Boolean = (path == "/api" || path.startsWith("/api/") || path == "/proxy" || path.startsWith("/proxy/")) && path !in PUBLIC_API
        private fun defaultServerName(): String =
            (System.getenv("CLOUDSTREAM_SERVER_NAME")?.takeIf { it.isNotBlank() } ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Cloudstream server")).take(100)
        /** The Worker is used only when `CLOUDSTREAM_AUTH_URL` is set; otherwise this server signs devices in itself. */
        private fun defaultBackend(): AuthBackend {
            val url = System.getenv("CLOUDSTREAM_AUTH_URL").orEmpty()
            if (url.isBlank()) return LocalBackend(File(Paths.dataDir, "owner.json"))
            val idFile = File(Paths.dataDir, "server-id")
            val id = if (idFile.exists()) idFile.readText().trim() else UUID.randomUUID().toString().also { idFile.parentFile?.mkdirs(); idFile.writeText(it) }
            return WorkerBackend(WorkerClient(url, System.getenv("CLOUDSTREAM_AUTH_SECRET").orEmpty(), id, defaultServerName()))
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
