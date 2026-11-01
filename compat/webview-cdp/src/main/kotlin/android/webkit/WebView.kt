package android.webkit

import android.content.Context
import android.net.Uri
import desktop.cdp.CdpEvent
import desktop.cdp.Chromium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.lang.reflect.Method
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The slice of Android's WebView that StreamCorner uses, backed by a tab of the shared Chromium. Each WebView is one
 * tab, opened on creation and closed by [destroy].
 *
 * - [loadUrl], [evaluateJavascript], [stopLoading] and [layout] are queued and sent in call order, as on Android,
 *   where they're posted to the WebView's thread; none of them blocks the caller.
 * - Every request of the page, its out-of-process iframes and its workers is paused (CDP `Fetch`) and offered to
 *   [WebViewClient.shouldInterceptRequest]. Top-frame navigations other than [loadUrl]'s go to
 *   [WebViewClient.shouldOverrideUrlLoading] first.
 * - [addJavascriptInterface] exposes the object's `@JavascriptInterface` methods to the page (see [expose]). Calls
 *   run on a "JavaBridge" thread and return their value, as on Android.
 *
 * Members outside this slice don't exist here; a plugin that calls one fails with `NoSuchMethodError`.
 */
open class WebView(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val settings = WebSettings.create { ua -> enqueue { tab -> tab.send("Network.setUserAgentOverride", buildJsonObject { put("userAgent", ua ?: Chromium.pageUserAgent()) }) } }
    @Volatile private var client = WebViewClient()
    private val interfaces = ConcurrentHashMap<String, Any>()
    private val actions = Channel<suspend (Chromium.Tab) -> Unit>(Channel.UNLIMITED)
    @Volatile private var destroyed = false
    /** The URL [loadUrl] last asked for; the top-frame request for it skips shouldOverrideUrlLoading. */
    @Volatile private var loading: String? = null
    @Volatile private var url: String? = null

    init {
        scope.launch {
            try {
                Chromium.withTab { tab -> run(tab) }
            } catch (e: Exception) {
                if (!destroyed) log.log(Level.WARNING, "WebView tab failed: ${e.message}")
            }
        }
    }

    fun getSettings(): WebSettings = settings

    fun setWebViewClient(client: WebViewClient) {
        this.client = client
    }

    fun getWebViewClient(): WebViewClient = client

    fun getUrl(): String? = url

    fun loadUrl(url: String) {
        enqueue { tab ->
            loading = url
            this.url = url
            tab.navigate(url)
        }
    }

    fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        enqueue { tab ->
            val reply = runCatching {
                tab.send("Runtime.evaluate", buildJsonObject {
                    put("expression", script)
                    put("returnByValue", true)
                })
            }
            if (TRACE) trace("evaluate ${script.take(120)} -> ${reply.getOrNull()?.toString()?.take(300) ?: reply.exceptionOrNull()}")
            val result = reply.getOrNull()?.get("result")?.jsonObject?.get("value")
            resultCallback?.let { cb -> withContext(Dispatchers.Main) { cb.onReceiveValue((result ?: JsonNull).toString()) } }
        }
    }

    fun addJavascriptInterface(obj: Any, name: String) {
        interfaces[name] = obj
        enqueue { tab -> expose(tab, name, obj) }
    }

    fun removeJavascriptInterface(name: String) {
        interfaces.remove(name)
    }

    fun stopLoading() {
        enqueue { tab -> tab.send("Page.stopLoading") }
    }

    /** Android sizes unattached views with this; it becomes the tab's viewport. */
    fun layout(l: Int, t: Int, r: Int, b: Int) {
        enqueue { tab ->
            tab.send("Emulation.setDeviceMetricsOverride", buildJsonObject {
                put("width", (r - l).coerceAtLeast(1))
                put("height", (b - t).coerceAtLeast(1))
                put("deviceScaleFactor", 1)
                put("mobile", false)
            })
        }
    }

    /** Closes the tab. Calls after this are ignored. */
    fun destroy() {
        destroyed = true
        actions.close()
    }

    private fun enqueue(action: suspend (Chromium.Tab) -> Unit) {
        if (!destroyed) actions.trySend(action)
    }

    // ---- the tab -----------------------------------------------------------------------------------------------

    private suspend fun run(tab: Chromium.Tab) = coroutineScope {
        setUp(tab, tab.sessionId)
        val events = launch { events(tab) }
        for (action in actions) {
            try {
                action(tab)
            } catch (e: Exception) {
                log.fine("WebView action failed: ${e.message}")
                if (TRACE) trace("action failed: $e")
            }
        }
        events.cancel()
    }

    /** Pauses every request of [session] (the tab, or an iframe or worker it attached) and attaches its children. */
    private suspend fun setUp(tab: Chromium.Tab, session: String) {
        tab.sendTo(session, "Fetch.enable", buildJsonObject {
            putJsonArray("patterns") { add(buildJsonObject { put("urlPattern", "*"); put("requestStage", "Request") }) }
        })
        tab.sendTo(session, "Target.setAutoAttach", buildJsonObject {
            put("autoAttach", true)
            put("waitForDebuggerOnStart", true)
            put("flatten", true)
        })
    }

    private suspend fun events(tab: Chromium.Tab) = coroutineScope {
        while (true) {
            val e = tab.next(Long.MAX_VALUE) ?: continue
            if (TRACE) trace("${e.method} [${e.sessionId?.take(6)}] ${e.params.toString().take(200)}")
                if (e === Chromium.Tab.CLOSED) {
                // Chromium went away: end the action loop too, so the tab's slot is released.
                actions.close()
                break
            }
            when (e.method) {
                "Fetch.requestPaused" -> launch(Dispatchers.IO) { onRequest(tab, e) }
                "Target.attachedToTarget" -> launch { onAttached(tab, e.params) }
                // A worker resumed before its script loaded waits again once it has; resume it for good.
                "Inspector.workerScriptLoaded" -> e.sessionId?.let { s -> launch { runCatching { tab.sendTo(s, "Runtime.runIfWaitingForDebugger") } } }
                "Page.loadEventFired" -> if (e.sessionId == tab.sessionId) url?.let { u ->
                    launch(Dispatchers.Main) { runCatching { client.onPageFinished(this@WebView, u) } }
                }
            }
        }
    }

    /**
     * An out-of-process iframe gets its own request interception before it starts. A dedicated worker has no Fetch
     * domain (its requests pause in its page's session), so it's only resumed.
     */
    private suspend fun onAttached(tab: Chromium.Tab, params: JsonObject) {
        val child = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
        val type = params["targetInfo"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
        if (TRACE) trace("attached $type ${child.take(6)}")
        if (type != "worker") runCatching { setUp(tab, child) }.onFailure { if (TRACE) trace("set up $type $child failed: $it") }
        runCatching { tab.sendTo(child, "Runtime.runIfWaitingForDebugger") }.onFailure { if (TRACE) trace("resume $child failed: $it") }
    }

    private suspend fun onRequest(tab: Chromium.Tab, e: CdpEvent) {
        val session = e.sessionId ?: return
        val p = e.params
        val id = p["requestId"]?.jsonPrimitive?.contentOrNull ?: return
        val r = p["request"]?.jsonObject ?: return
        val url = r["url"]?.jsonPrimitive?.contentOrNull ?: return
        if (runCatching { java.net.URI(url).path }.getOrNull() == BRIDGE_PATH) {
            tab.sendTo(session, "Fetch.fulfillRequest", bridgeCall(id, r))
            return
        }
        val mainFrame = session == tab.sessionId && p["resourceType"]?.jsonPrimitive?.contentOrNull == "Document" &&
            p["frameId"]?.jsonPrimitive?.contentOrNull == tab.targetId
        val request = Request(
            Uri.parse(url),
            r["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
            r["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            mainFrame,
        )
        try {
            if (mainFrame && url != loading && client.shouldOverrideUrlLoading(this, request)) {
                tab.sendTo(session, "Fetch.failRequest", buildJsonObject { put("requestId", id); put("errorReason", "Aborted") })
                return
            }
            val response = client.shouldInterceptRequest(this, request)
            if (TRACE) trace("request $id $url -> ${if (response == null) "continue" else "fulfill ${response.getStatusCode()}"}")
            if (response == null) {
                tab.sendTo(session, "Fetch.continueRequest", buildJsonObject { put("requestId", id) })
                return
            }
            if (mainFrame) this.url = url
            tab.sendTo(session, "Fetch.fulfillRequest", fulfill(id, response))
        } catch (ex: Exception) {
            log.fine("Request $url: ${ex.message}")
            if (TRACE) trace("request $url failed: $ex")
            runCatching { tab.sendTo(session, "Fetch.continueRequest", buildJsonObject { put("requestId", id) }) }
        }
    }

    private fun fulfill(id: String, response: WebResourceResponse) = buildJsonObject {
        val body = response.getData()?.use { it.readBytes() } ?: ByteArray(0)
        val headers = response.getResponseHeaders().orEmpty().toMutableMap()
        if (headers.keys.none { it.equals("Content-Type", true) }) response.getMimeType()?.let { mime ->
            headers["Content-Type"] = response.getEncoding()?.let { "$mime; charset=$it" } ?: mime
        }
        put("requestId", id)
        put("responseCode", response.getStatusCode())
        response.getReasonPhrase()?.takeIf { it.isNotEmpty() }?.let { put("responsePhrase", it) }
        putJsonArray("responseHeaders") {
            headers.forEach { (k, v) -> add(buildJsonObject { put("name", k); put("value", v) }) }
        }
        put("body", Base64.getEncoder().encodeToString(body))
    }

    // ---- the JavaScript bridge -----------------------------------------------------------------------------------

    /**
     * Defines `window[name]` with one stub per `@JavascriptInterface` method. A stub posts `{o, m, a}` to
     * [BRIDGE_PATH] on the page's own origin with a synchronous XHR, which [onRequest] answers with the method's
     * return value. So, as on Android, calls run in order and return their value, and no CDP domain the page could
     * notice (Runtime) has to be enabled.
     */
    private suspend fun expose(tab: Chromium.Tab, name: String, obj: Any) {
        val stubs = bridgeMethods(obj).keys.joinToString(",") { m ->
            "${js(m)}:function(){var x=new XMLHttpRequest();x.open('POST',${js(BRIDGE_PATH)},false);" +
                "x.send(JSON.stringify({o:${js(name)},m:${js(m)},a:Array.prototype.slice.call(arguments)}));" +
                "return x.responseText===''?undefined:JSON.parse(x.responseText)}"
        }
        val script = "(function(){Object.defineProperty(globalThis,${js(name)},{value:{$stubs},configurable:true})})()"
        tab.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", script) })
        runCatching { tab.send("Runtime.evaluate", buildJsonObject { put("expression", script) }) }
    }

    private fun js(s: String) = JsonPrimitive(s).toString()

    /** Runs one bridge call on the JavaBridge thread and builds the reply carrying its return value. */
    private fun bridgeCall(id: String, request: JsonObject): JsonObject {
        val body = request["postData"]?.jsonPrimitive?.contentOrNull
            ?: request["postDataEntries"]?.let { entries ->
                (entries as JsonArray).joinToString("") { e ->
                    e.jsonObject["bytes"]?.jsonPrimitive?.contentOrNull?.let { String(Base64.getDecoder().decode(it)) }.orEmpty()
                }
            }
        val result = runCatching {
            val call = Json.parseToJsonElement(body!!).jsonObject
            val name = call["o"]!!.jsonPrimitive.content
            val method = call["m"]!!.jsonPrimitive.content
            val args = call["a"] as? JsonArray ?: JsonArray(emptyList())
            val obj = interfaces[name] ?: error("No JavaScript interface $name")
            val m = bridgeMethods(obj)[method].orEmpty().firstOrNull { it.parameterCount == args.size }
                ?: error("$name.$method(${args.size} args) isn't a JavascriptInterface method")
            javaBridge.submit<Any?> {
                m.invoke(obj, *m.parameterTypes.mapIndexed { i, type -> convert(args[i], type) }.toTypedArray())
            }.get()
        }.onFailure { log.log(Level.WARNING, "JavaScript bridge call failed", (it as? java.util.concurrent.ExecutionException)?.cause ?: it) }
            .getOrNull()
        val reply = when (result) {
            null, Unit -> ""
            is String -> JsonPrimitive(result).toString()
            is Number -> JsonPrimitive(result).toString()
            is Boolean -> JsonPrimitive(result).toString()
            else -> JsonPrimitive(result.toString()).toString()
        }
        return buildJsonObject {
            put("requestId", id)
            put("responseCode", 200)
            putJsonArray("responseHeaders") { add(buildJsonObject { put("name", "Content-Type"); put("value", "application/json") }) }
            put("body", Base64.getEncoder().encodeToString(reply.toByteArray()))
        }
    }

    /** The object's `@JavascriptInterface` methods by name (the annotation is matched by name, like the app's). */
    private fun bridgeMethods(obj: Any): Map<String, List<Method>> =
        obj.javaClass.methods.filter { m -> m.annotations.any { it.annotationClass.java.name == JavascriptInterface::class.java.name } }
            .onEach { it.isAccessible = true }
            .groupBy { it.name }

    private fun convert(v: JsonElement, type: Class<*>): Any? {
        val p = v as? JsonPrimitive
        return when (type) {
            String::class.java -> if (v is JsonNull) null else p?.contentOrNull ?: v.toString()
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> p?.doubleOrNull?.toInt() ?: 0
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> p?.longOrNull ?: p?.doubleOrNull?.toLong() ?: 0L
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> p?.doubleOrNull ?: 0.0
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> p?.doubleOrNull?.toFloat() ?: 0f
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> p?.booleanOrNull ?: false
            else -> null
        }
    }

    private class Request(
        private val url: Uri,
        private val method: String,
        private val headers: Map<String, String>,
        private val mainFrame: Boolean,
    ) : WebResourceRequest {
        override fun getUrl() = url
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = headers
    }

    companion object {
        private val log = Logger.getLogger("android.webkit.WebView")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val javaBridge = Executors.newSingleThreadExecutor { r -> Thread(r, "JavaBridge").apply { isDaemon = true } }
        /** Same-origin path the JavaScript bridge's stubs post to; answered here, never sent to the network. */
        private const val BRIDGE_PATH = "/__cs_javabridge__"

        /** Debug aid: `-Dcs.debug.webview` prints every CDP event and failure of the emulated WebViews to stderr. */
        private val TRACE = System.getProperty("cs.debug.webview") != null
        private fun trace(line: String) = System.err.println("WebView: $line")

        @JvmStatic
        fun setWebContentsDebuggingEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {}
    }
}
