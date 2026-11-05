package desktop.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

class AuthFailure(val status: Int, override val message: String) : RuntimeException(message)

/** The shared service secret is sent only to the configured HTTPS Worker, never to clients or redirects. */
open class WorkerClient(
    val origin: String,
    private val secret: String,
    private val serverId: String,
    val serverName: String,
    private val allowLocalHttp: Boolean = false,
) {
    val configured: Boolean get() = origin.isNotBlank() && secret.length >= 32
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    init {
        if (origin.isNotBlank()) {
            val uri = URI(origin)
            require(uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1"))) { "CLOUDSTREAM_AUTH_URL must use HTTPS" }
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.path.orEmpty() in listOf("", "/") || allowLocalHttp)) { "CLOUDSTREAM_AUTH_URL must be a Worker origin without a path" }
        }
    }

    open suspend fun request(action: String, values: Map<String, String> = emptyMap()): JsonObject = withContext(Dispatchers.IO) {
        if (!configured) throw AuthFailure(503, "Authentication is not configured on this server")
        val body = buildJsonObject {
            put("serverId", JsonPrimitive(serverId))
            put("serverName", JsonPrimitive(serverName))
            values.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        try {
            val req = Request.Builder().url("${origin.trimEnd('/')}/api/server/$action")
                .header("Authorization", "Bearer $secret")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { response ->
                val raw = response.body.string()
                val result = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrElse { throw AuthFailure(503, "Authentication service unavailable. Try again.") }
                if (!response.isSuccessful) {
                    // A service credential failure is a server setup error, not a bad owner password.
                    val status = if (response.code == 401 && action != "login") 503 else response.code.takeIf { it in setOf(400, 401, 410, 429) } ?: 503
                    throw AuthFailure(status, if (status == 503) "Authentication service unavailable. Check the server configuration." else result["error"]?.jsonPrimitive?.contentOrNull ?: "Sign-in failed")
                }
                result
            }
        } catch (e: AuthFailure) { throw e }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw AuthFailure(503, "Authentication service unavailable. Try again.") }
    }
}
