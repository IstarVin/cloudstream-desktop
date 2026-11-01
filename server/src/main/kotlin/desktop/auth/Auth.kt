package desktop.auth

import desktop.config.Paths
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LAN access control. Loopback requests never need a token; every other request to `/api` or `/proxy` needs the
 * pairing cookie (set by opening `/pair?token=…`, which the desktop shows as a QR code) or a bearer token.
 */
class Auth(private val file: File = File(Paths.dataDir, "token")) {
    @Volatile var token: String = file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 32 } ?: rotate()
        private set

    fun rotate(): String {
        val t = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        file.writeText(t)
        file.setReadable(false, false)
        file.setReadable(true, true)
        token = t
        return t
    }

    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    fun isLoopback(call: ApplicationCall): Boolean = isLoopback(call.request.origin.remoteAddress)

    fun isAuthorized(call: ApplicationCall): Boolean =
        isAuthorized(call.request.origin.remoteAddress, call.request.cookies[COOKIE], call.request.headers["Authorization"])

    /** Loopback needs nothing; anyone else needs the pairing cookie or `Authorization: Bearer <token>`. */
    fun isAuthorized(remoteAddress: String, cookie: String?, authorization: String?): Boolean {
        if (isLoopback(remoteAddress)) return true
        val bearer = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        return matches(cookie) || matches(bearer)
    }

    /** Non-loopback IPv4 addresses, for the pairing URL. */
    fun lanAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
        .map { it.hostAddress }

    companion object {
        const val COOKIE = "cs_token"
        /** Paths reachable without a token: the static UI (it shows the pairing screen) and these. */
        private val PUBLIC_API = setOf("/api/auth/status", "/pair")

        fun isLoopback(remoteAddress: String): Boolean =
            runCatching { InetAddress.getByName(remoteAddress).isLoopbackAddress }.getOrDefault(false)

        /** The API, the WebSocket and the stream proxy need a token; the static UI and pairing don't. */
        fun needsToken(path: String): Boolean =
            (path.startsWith("/api/") || path.startsWith("/proxy/")) && path !in PUBLIC_API

        fun plugin(auth: Auth) = createApplicationPlugin("LanAuth") {
            onCall { call ->
                if (needsToken(call.request.path()) && !auth.isAuthorized(call)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Pair this device first"))
                }
            }
        }
    }
}
