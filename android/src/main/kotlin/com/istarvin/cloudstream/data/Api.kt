package com.istarvin.cloudstream.data

import desktop.api.AuthResult
import desktop.api.DeviceAuthorization
import desktop.api.AuthStatus
import desktop.api.Bookmark
import desktop.api.BrowserStatusDto
import desktop.api.DeviceInfo
import desktop.api.PlayRequest
import desktop.api.PlayerCommand
import desktop.api.BookmarkRequest
import desktop.api.ContinueEntry
import desktop.api.Details
import desktop.api.HistoryEntry
import desktop.api.HomePage
import desktop.api.HomeSection
import desktop.api.LinkDto
import desktop.api.LinkReport
import desktop.api.PluginDto
import desktop.api.PluginSettingsDto
import desktop.api.PluginSourceDto
import desktop.api.ProgressRequest
import desktop.api.ProviderDto
import desktop.api.ResumeDto
import desktop.api.SearchResults
import desktop.api.StatusDto
import desktop.api.StreamDto
import desktop.api.SubtitleDto
import desktop.api.UpdateResult
import desktop.player.SkipRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

/** The chosen server and this device's session token. */
@Serializable
data class ServerConfig(val baseUrl: String, val token: String) {
    val url: HttpUrl get() = baseUrl.toHttpUrl()

    companion object {
        /** Normalize a server address; credentials never come from URLs. */
        fun parse(text: String, token: String? = null): ServerConfig? {
            val raw = text.trim().let { if ("://" in it) it else "http://$it" }
            val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath !in listOf("", "/") || url.query != null || url.fragment != null) return null
            val t = token?.trim().orEmpty()
            val base = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return ServerConfig(base, t)
        }
    }
}

class ApiException(val code: Int, message: String) : IOException(message)

/** One server-sent event. */
data class SseEvent(val event: String, val data: String)

/**
 * The desktop server's `/api`, over the same OkHttp client that loads posters, subtitles and video, so every request
 * to the server carries this device's session token.
 */
class Api(initial: ServerConfig?) {
    @Volatile var server: ServerConfig? = initial

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when the session expires or is revoked. */
    val unauthorized: SharedFlow<Unit> = _unauthorized

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val s = server
            // Only the paired server gets the token; direct stream hosts and image CDNs don't.
            val authed = if (s != null && req.url.scheme == s.url.scheme && req.url.host == s.url.host && req.url.port == s.url.port) {
                req.newBuilder().header("Authorization", "Bearer ${s.token}").build()
            } else req
            val res = chain.proceed(authed)
            if (res.code == 401 && authed !== req) _unauthorized.tryEmit(Unit)
            res
        }
        .build()

    /** A server path (or a root-relative `/proxy/…` URL from the API) as an absolute URL. */
    fun resolve(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = server?.baseUrl ?: throw IOException("Not paired with a server")
        return base + (if (path.startsWith('/')) path else "/$path")
    }

    private fun url(path: String, query: Map<String, Any?> = emptyMap()): HttpUrl =
        resolve(path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()

    private val authHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    private suspend fun call(request: Request, client: OkHttpClient = http): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body.string() }
                if (response.isSuccessful) cont.resume(body)
                else cont.resumeWithException(ApiException(response.code, errorMessage(body) ?: "Server answered ${response.code}"))
            }
        })
    }

    private fun errorMessage(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()

    private suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        json.decodeFromString(call(Request.Builder().url(url(path, query)).build()))

    private fun body(value: String): RequestBody = value.toRequestBody("application/json".toMediaType())

    private suspend inline fun <reified B> send(method: String, path: String, value: B, query: Map<String, Any?> = emptyMap()): String =
        call(Request.Builder().url(url(path, query)).method(method, body(json.encodeToString(value))).build())

    private suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()) {
        call(Request.Builder().url(url(path, query)).delete().build())
    }

    /** Server-sent events from [path], until the server sends `done` or the collector stops. */
    fun sse(path: String, query: Map<String, Any?>): Flow<SseEvent> = callbackFlow {
        val request = Request.Builder().url(url(path, query)).header("Accept", "text/event-stream").build()
        val client = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(SseEvent(type ?: "message", data))
                if (type == "done") channel.close()
            }

            override fun onClosed(eventSource: EventSource) { channel.close() }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val err = when {
                    response != null && !response.isSuccessful ->
                        ApiException(response.code, response.body.string().let(::errorMessage) ?: "Server answered ${response.code}")
                    else -> t ?: IOException("Connection lost")
                }
                channel.close(err)
            }
        })
        awaitClose { source.cancel() }
    }.flowOn(Dispatchers.IO)

    // ---- endpoints ----------------------------------------------------------------------------------------------

    private suspend inline fun <reified T> authRequest(baseUrl: String, path: String, values: Map<String, String>? = null): T {
        val request = Request.Builder().url(baseUrl + "/api/auth/" + path)
        if (values != null) request.post(body(json.encodeToString(values)))
        return json.decodeFromString(call(request.build(), authHttp))
    }

    suspend fun authStatus(config: ServerConfig): AuthStatus {
        val req = Request.Builder().url(config.baseUrl + "/api/auth/status")
            .header("Authorization", "Bearer ${config.token}").build()
        return json.decodeFromString(call(req, authHttp))
    }
    suspend fun startSignIn(baseUrl: String, deviceName: String): DeviceAuthorization =
        authRequest(baseUrl, "device", mapOf("deviceName" to deviceName))
    suspend fun pollSignIn(baseUrl: String, deviceCode: String): AuthResult =
        authRequest(baseUrl, "device/poll?client=android", mapOf("deviceCode" to deviceCode))
    suspend fun login(baseUrl: String, username: String, password: String, deviceName: String): AuthResult =
        authRequest(baseUrl, "login?client=android", mapOf("username" to username, "password" to password, "deviceName" to deviceName))
    suspend fun logout() { send("POST", "/api/auth/logout", emptyMap<String, String>()) }

    suspend fun status(): StatusDto = get("/api/status")
    suspend fun providers(): List<ProviderDto> = get("/api/providers")
    suspend fun homeSections(provider: String): List<HomeSection> = get("/api/providers/${enc(provider)}/home")
    suspend fun home(provider: String, section: Int, page: Int = 1): HomePage =
        get("/api/providers/${enc(provider)}/home/$section", mapOf("page" to page))

    fun search(q: String, providers: List<String>?): Flow<SseEvent> =
        sse("/api/search", mapOf("q" to q, "providers" to providers?.joinToString(",")))

    suspend fun details(provider: String, url: String): Details = get("/api/load", mapOf("provider" to provider, "url" to url))

    /** [fresh] makes the server run the plugin's `loadLinks` again instead of reusing its cached set. */
    fun links(provider: String, data: String, fresh: Boolean = false): Flow<SseEvent> =
        sse("/api/links", mapOf("provider" to provider, "data" to data, "fresh" to if (fresh) 1 else null))

    suspend fun stream(link: String, direct: Boolean): StreamDto =
        // Decrypt on the server: device ClearKey can fail, and Media3 ignores identity SAMPLE-AES in fMP4 HLS.
        get("/api/stream", mapOf("link" to link, "direct" to if (direct) 1 else null, "decrypt" to 1))

    fun subtitleUrl(sub: SubtitleDto): String = url("/api/subtitle", mapOf("id" to sub.id)).toString()

    suspend fun report(link: String, success: Boolean) {
        send("POST", "/api/links/report", LinkReport(link, success))
    }

    suspend fun resume(provider: String, data: String): Double? =
        get<ResumeDto>("/api/resume", mapOf("provider" to provider, "data" to data)).position

    suspend fun saveProgress(req: ProgressRequest) {
        send("POST", "/api/progress", req)
    }

    suspend fun skips(malId: Int?, aniListId: Int?, episode: Int, duration: Double): List<SkipRange> =
        get("/api/skips", mapOf("malId" to malId, "aniListId" to aniListId, "episode" to episode, "duration" to duration))

    suspend fun history(): List<HistoryEntry> = get("/api/history")
    suspend fun forget(provider: String, url: String) = delete("/api/history", mapOf("provider" to provider, "url" to url))
    suspend fun clearHistory() = delete("/api/history/all")
    suspend fun continueWatching(): List<ContinueEntry> = get("/api/continue")
    suspend fun bookmarks(): List<Bookmark> = get("/api/bookmarks")
    suspend fun setBookmark(req: BookmarkRequest) {
        send("POST", "/api/bookmarks", req)
    }

    suspend fun settings(): ServerSettings = json.decodeFromString(call(Request.Builder().url(url("/api/settings")).build()))

    /** Saves some settings (`/api/settings` takes partial updates) and returns them all. */
    suspend fun updateSettings(values: JsonObject): ServerSettings =
        json.decodeFromString(call(Request.Builder().url(url("/api/settings")).put(body(values.toString())).build()))

    suspend fun plugins(): List<PluginDto> = get("/api/plugins")
    suspend fun setPluginEnabled(name: String, enabled: Boolean) {
        send("PUT", "/api/plugins/${enc(name)}/enabled", JsonObject(mapOf("enabled" to JsonPrimitive(enabled))))
    }

    suspend fun pluginSource(): PluginSourceDto = get("/api/plugins/source")

    private suspend fun post(path: String, payload: String = "{}"): String =
        call(Request.Builder().url(url(path)).post(body(payload)).build())

    suspend fun checkPluginUpdates(): PluginSourceDto = json.decodeFromString(post("/api/plugins/check-updates"))

    /** Updates the named plugins (null: every one with an update); returns the ones that failed, with why. */
    suspend fun updatePlugins(modules: List<String>? = null): UpdateResult {
        val body = JsonObject(if (modules == null) emptyMap() else mapOf("modules" to kotlinx.serialization.json.JsonArray(modules.map(::JsonPrimitive))))
        return json.decodeFromString(post("/api/plugins/update", body.toString()))
    }

    suspend fun rollbackPlugin(name: String): PluginSourceDto = json.decodeFromString(post("/api/plugins/${enc(name)}/rollback"))

    suspend fun pluginSettings(name: String): PluginSettingsDto = get("/api/plugins/${enc(name)}/settings")

    suspend fun savePluginSettings(name: String, settings: PluginSettingsDto): PluginSettingsDto =
        json.decodeFromString(call(Request.Builder().url(url("/api/plugins/${enc(name)}/settings")).put(body(json.encodeToString(settings))).build()))

    suspend fun rebuildPlugins() {
        post("/api/plugins/rebuild")
    }

    suspend fun buildLog(): String = call(Request.Builder().url(url("/api/plugins/build-log")).build())

    // ---- the desktop's background browser (Cloudflare checks) ----------------------------------------------------

    suspend fun browser(): BrowserStatusDto = get("/api/browser")
    suspend fun solveCheck(id: String): BrowserStatusDto = json.decodeFromString(post("/api/browser/checks/${enc(id)}/solve"))
    suspend fun dismissCheck(id: String): BrowserStatusDto =
        json.decodeFromString(call(Request.Builder().url(url("/api/browser/checks/${enc(id)}")).delete().build()))
    suspend fun stopBrowser(): BrowserStatusDto = json.decodeFromString(post("/api/browser/stop"))
    suspend fun forgetClearance(host: String): BrowserStatusDto =
        json.decodeFromString(call(Request.Builder().url(url("/api/browser/clearances", mapOf("host" to host))).delete().build()))

    // ---- casting: the same play and commands, on any connected device ("mpv" is the desktop's) ------------------

    suspend fun castDevices(): List<DeviceInfo> = get("/api/cast/devices")

    suspend fun castPlay(device: String, req: PlayRequest, from: String?) {
        send("POST", "/api/cast/${enc(device)}/play", req, mapOf("from" to from))
    }

    suspend fun castCommand(device: String, cmd: String, body: PlayerCommand = PlayerCommand()) {
        send("POST", "/api/cast/${enc(device)}/${enc(cmd)}", body)
    }

    /** The casting socket. Pings keep it alive through idle Wi-Fi and notice a desktop that went away. */
    fun castSocket(listener: WebSocketListener): WebSocket =
        http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
            .newWebSocket(Request.Builder().url(url("/api/cast")).build(), listener)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Decodes a `link` event of `/api/links`. */
fun SseEvent.link(): LinkDto = json.decodeFromString(data)
fun SseEvent.subtitle(): SubtitleDto = json.decodeFromString(data)
fun SseEvent.results(): SearchResults = json.decodeFromString(data)
fun SseEvent.error(): String? = runCatching { json.parseToJsonElement(data).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
fun SseEvent.provider(): String? = runCatching { json.parseToJsonElement(data).jsonObject["provider"]?.jsonPrimitive?.content }.getOrNull()
