package desktop.auth

import desktop.api.AuthStatus
import desktop.api.DeviceAuthorization
import io.ktor.http.Cookie
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private class Attempts {
    private val entries = mutableMapOf<String, Pair<Long, Int>>()
    @Synchronized fun check(key: String) {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { now - it.value.first >= 60_000 }
        val previous = entries[key] ?: (now to 0)
        if (entries.size >= 4096 || previous.second >= 60) throw AuthFailure(429, "Too many requests. Try again in a minute.")
        entries[key] = previous.first to previous.second + 1
    }
}

fun Route.authRoutes(auth: Auth) {
    val attempts = Attempts()
    route("/api/auth") {
        get("/status") {
            call.respond(AuthStatus(auth.isAuthorized(call), auth.isLoopback(call), auth.worker.configured, auth.worker.origin, auth.worker.serverName))
        }
        suspend fun io.ktor.server.application.ApplicationCall.input(): JsonObject {
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
        fun JsonObject.field(key: String, max: Int = 256): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= max }
                ?: throw AuthFailure(400, "Invalid $key")
        suspend fun io.ktor.server.application.ApplicationCall.complete(name: String, epoch: Long) {
            auth.acceptEpoch(epoch)
            val issued = auth.issue(name, epoch)
            response.cookies.append(Cookie(Auth.COOKIE, issued.token, maxAge = Auth.SESSION_SECONDS, httpOnly = true,
                secure = request.origin.scheme == "https" || auth.publicOrigin != null, path = "/", extensions = mapOf("SameSite" to "Strict")))
            respond(buildJsonObject {
                put("status", JsonPrimitive("approved"))
                put("expiresAt", JsonPrimitive(issued.expiresAt))
                if (request.queryParameters["client"] == "android") put("token", JsonPrimitive(issued.token))
            })
        }
        post("/device") {
            val data = call.input()
            val result = auth.worker.request("device", mapOf("deviceName" to data.field("deviceName", 100)))
            call.respond(DeviceAuthorization(result["userCode"]!!.jsonPrimitive.content, result["deviceCode"]!!.jsonPrimitive.content,
                result["verificationUri"]!!.jsonPrimitive.content, result["expiresIn"]!!.jsonPrimitive.int, result["interval"]!!.jsonPrimitive.int))
        }
        post("/device/poll") {
            val data = call.input()
            val result = auth.worker.request("poll", mapOf("deviceCode" to data.field("deviceCode", 64)))
            if (result["status"]?.jsonPrimitive?.content == "approved")
                call.complete(result["deviceName"]!!.jsonPrimitive.content, result["epoch"]!!.jsonPrimitive.long)
            else call.respond(mapOf("status" to "pending"))
        }
        post("/login") {
            val data = call.input()
            val origin = auth.origin(call)
            val uri = java.net.URI(origin)
            if (uri.scheme != "https" && uri.host !in setOf("localhost", "127.0.0.1", "::1", "[::1]"))
                throw AuthFailure(400, "Use device-code sign-in on an HTTP server, or connect over HTTPS")
            val result = auth.worker.request("login", mapOf("username" to data.field("username", 64), "password" to data.field("password"), "clientIp" to call.request.origin.remoteAddress))
            call.complete(data.field("deviceName", 100), result["epoch"]!!.jsonPrimitive.long)
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
