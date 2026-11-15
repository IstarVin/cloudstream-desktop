package desktop.auth

import desktop.api.AuthStatus
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

fun Route.authRoutes(auth: Auth) {
    val attempts = RateLimit(60, 60_000)
    // Each approval is a lookup then a decision; this allows a few typos without letting a stolen session guess codes.
    val approvals = RateLimit(10, 5 * 60_000L, "Too many codes entered. Try again in a few minutes.")
    route("/api/auth") {
        get("/status") {
            call.respond(AuthStatus(auth.isAuthorized(call), auth.isLoopback(call), auth.backend.configured, auth.backend.workerUrl, auth.serverName,
                auth.backend.mode, auth.local?.passwordSet ?: false, auth.claimable()))
        }
        suspend fun ApplicationCall.input(): JsonObject {
            attempts.check(request.origin.remoteAddress)
            if (!request.headers["Content-Type"].orEmpty().startsWith("application/json")) throw AuthFailure(400, "JSON required")
            val channel = receiveChannel()
            val bytes = ByteArray(4097)
            var size = 0
            while (size < bytes.size) {
                val count = channel.readAvailable(bytes, size, bytes.size - size)
                if (count == -1) break
                size += count
            }
            if (size > 4096) throw AuthFailure(400, "Request too large")
            val raw = bytes.copyOf(size).decodeToString()
            return runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrElse { throw AuthFailure(400, "Invalid JSON object") }
        }
        fun JsonObject.optional(key: String, max: Int = 256): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }?.also { if (it.length > max) throw AuthFailure(400, "Invalid $key") }
        fun JsonObject.field(key: String, max: Int = 256): String = optional(key, max) ?: throw AuthFailure(400, "Invalid $key")
        fun ApplicationCall.localOnly(): LocalBackend = auth.local ?: throw AuthFailure(404, "This server signs in through the Worker")
        suspend fun ApplicationCall.complete(issued: Auth.Issued) {
            val client = request.queryParameters["client"]
            // Native clients and the extension keep a Bearer token; only the web UI gets the cookie.
            if (client != "extension") response.cookies.append(Cookie(Auth.COOKIE, issued.token, maxAge = Auth.SESSION_SECONDS, httpOnly = true,
                secure = request.origin.scheme == "https" || auth.publicOrigin != null, path = "/", extensions = mapOf("SameSite" to "Strict")))
            respond(buildJsonObject {
                put("status", JsonPrimitive("approved"))
                put("expiresAt", JsonPrimitive(issued.expiresAt))
                if (client == "android" || client == "extension") put("token", JsonPrimitive(issued.token))
            })
        }
        suspend fun ApplicationCall.complete(name: String, epoch: Long) {
            auth.acceptEpoch(epoch)
            complete(auth.issue(name, epoch))
        }
        post("/device") {
            val data = call.input()
            call.respond(auth.backend.startDevice(data.field("deviceName", 100), call.request.origin.remoteAddress, auth.origin(call)))
        }
        post("/device/poll") {
            val data = call.input()
            val approved = auth.backend.poll(data.field("deviceCode", 64))
            if (approved != null) call.complete(approved.deviceName, approved.epoch)
            else call.respond(mapOf("status" to "pending"))
        }
        post("/login") {
            val data = call.input()
            val origin = auth.origin(call)
            val uri = java.net.URI(origin)
            if (uri.scheme != "https" && uri.host !in setOf("localhost", "127.0.0.1", "::1", "[::1]"))
                throw AuthFailure(400, "Use device-code sign-in on an HTTP server, or connect over HTTPS")
            val epoch = auth.backend.login(data.optional("username", 64), data.field("password"), call.request.origin.remoteAddress)
            call.complete(data.field("deviceName", 100), epoch)
        }
        // The one-time link from the server log (local mode, nobody signed in). Public, but the code is 256 bits and single use.
        post("/claim") {
            val data = call.input()
            call.localOnly()
            call.complete(auth.claim(data.field("code", 64), data.field("deviceName", 100)))
        }
        // Approving another device's code from one that is already signed in (local mode).
        post("/device/lookup") {
            val data = call.input()
            val local = call.localOnly()
            approvals.check(auth.token(call).orEmpty())
            call.respond(local.lookup(data.field("userCode", 16)))
        }
        post("/device/approve") {
            val data = call.input()
            val local = call.localOnly()
            approvals.check(auth.token(call).orEmpty())
            val approve = (data["approve"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw AuthFailure(400, "Choose approve or decline")
            local.approve(data.field("userCode", 16), approve)
            call.respond(mapOf("ok" to true))
        }
        // Sets or changes the owner password; other devices are signed out so a leaked password stops working everywhere.
        put("/password") {
            val data = call.input()
            val local = call.localOnly()
            if (local.passwordSet) local.checkPassword(data.optional("currentPassword") ?: throw AuthFailure(400, "Enter your current password"), call.request.origin.remoteAddress)
            local.setPassword(data.field("password"))
            auth.revokeOthers(auth.token(call))
            call.respond(mapOf("ok" to true))
        }
        post("/logout") {
            auth.logout(auth.token(call))
            call.response.cookies.append(Cookie(Auth.COOKIE, "", maxAge = 0, httpOnly = true, path = "/", secure = call.request.origin.scheme == "https" || auth.publicOrigin != null, extensions = mapOf("SameSite" to "Strict")))
            call.respond(mapOf("ok" to true))
        }
        get("/sessions") { call.respond(auth.list(auth.token(call))) }
        delete("/sessions/{id}") { auth.revoke(call.parameters["id"].orEmpty()); call.respond(mapOf("ok" to true)) }
        post("/revoke-all") { auth.revokeAll(); call.respond(mapOf("ok" to true)) }
    }
}
