package desktop.cdp

import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/** Chromium can't be used here (not installed, no display for a visible window, or it failed to start). */
class ChromiumUnavailableException(message: String) : IOException(message)

/**
 * One shared, long-lived Chromium with a persistent profile, driven over CDP. It starts on the first request that
 * needs a browser and stops after it has been idle for a while. Every resolve or solve gets its own tab.
 *
 * When a check can't be passed in the background (an interactive Turnstile, or a stricter site), it is listed in
 * [challenges]; [solveManually] opens it in a visible window, and whatever cookies that earns are reused.
 */
object Chromium {
    private val log = Logger.getLogger("desktop.cdp.Chromium")

    enum class Mode { HEADLESS, XVFB, HEADED;
        val id get() = name.lowercase()
        companion object {
            fun parse(s: String?) = entries.firstOrNull { it.id == s?.lowercase() } ?: HEADLESS
        }
    }

    data class Options(
        val path: String = "chromium",
        val mode: Mode = Mode.HEADLESS,
        /** Holds the profile, the saved clearances and chromium.log. */
        val dataDir: File = File(System.getProperty("java.io.tmpdir"), "cloudstream-desktop"),
        val idleMs: Long = 10 * 60_000L,
        /** How long a background Cloudflare solve may take before it is offered as a manual check. */
        val solveTimeoutMs: Long = 45_000L,
        /** How long a request then waits for someone to pass the check by hand. */
        val manualWaitMs: Long = 120_000L,
    )

    /** A check the background browser couldn't pass. [kind] is `cloudflare` or `page` (a WebViewResolver page). */
    data class Challenge(val id: String, val kind: String, val host: String, val url: String, val since: Long, val solving: Boolean = false)

    data class Status(
        val running: Boolean,
        val mode: String?,
        val configuredMode: String,
        val executable: String?,
        val userAgent: String?,
        val error: String?,
        val challenges: List<Challenge>,
        val clearances: List<String>,
    )

    class Tab internal constructor(private val conn: CdpConnection, val targetId: String, val sessionId: String) {
        internal val events = Channel<CdpEvent>(Channel.UNLIMITED)

        suspend fun send(method: String, params: JsonObject = CdpConnection.EMPTY) = conn.send(method, params, sessionId)

        /** Sends to one of this tab's child sessions (an out-of-process iframe or a worker it auto-attached). */
        suspend fun sendTo(session: String, method: String, params: JsonObject = CdpConnection.EMPTY) = conn.send(method, params, session)

        suspend fun navigate(url: String, referrer: String? = null) {
            send("Page.navigate", buildJsonObject {
                put("url", url)
                referrer?.let { put("referrer", it) }
            })
        }

        /** Runs [expression] in the page and returns its value (promises are awaited). */
        suspend fun evaluate(expression: String): JsonElement? =
            send("Runtime.evaluate", buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
                put("awaitPromise", true)
            })["result"]?.jsonObject?.get("value")

        /** The next event for this tab; null on timeout, [CLOSED] once the tab or the browser is gone. */
        suspend fun next(timeoutMs: Long): CdpEvent? = withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
            events.receiveCatching().getOrNull() ?: CLOSED
        }

        companion object {
            val CLOSED = CdpEvent("desktop.closed", CdpConnection.EMPTY, null)
        }
    }

    private class Browser(val process: Process, val xvfb: Process?, val conn: CdpConnection, val mode: Mode) {
        val tabs = ConcurrentHashMap<String, Tab>()
        val alive get() = !conn.closed && process.isAlive
    }

    @Volatile var options = Options()
        private set
    @Volatile private var browser: Browser? = null
    @Volatile private var lastError: String? = null
    @Volatile private var lastUsed = 0L
    private val lock = Mutex()
    private val active = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userAgents = ConcurrentHashMap<String, String>()
    private val ids = AtomicInteger()

    private val _challenges = MutableStateFlow<List<Challenge>>(emptyList())
    val challenges: StateFlow<List<Challenge>> = _challenges

    init {
        scope.launch {
            while (true) {
                delay(30_000)
                val b = browser ?: continue
                val idle = System.currentTimeMillis() - lastUsed > options.idleMs
                if (active.get() == 0 && idle && _challenges.value.none { it.solving }) {
                    log.info("Stopping Chromium after ${options.idleMs / 60_000} idle minutes")
                    lock.withLock { if (browser === b) shutdown(b) }
                }
                expireChallenges()
            }
        }
    }

    fun configure(o: Options) {
        options = o
        o.dataDir.mkdirs()
        Clearances.load(File(o.dataDir, "clearances.json"))
        Clearances.userAgent?.let { WebViewResolver.webViewUserAgent = it }
    }

    fun setMode(mode: Mode) {
        options = options.copy(mode = mode)
    }

    fun status() = Status(
        running = browser?.alive == true,
        mode = browser?.takeIf { it.alive }?.mode?.id,
        configuredMode = options.mode.id,
        executable = executable(),
        userAgent = WebViewResolver.webViewUserAgent,
        error = lastError,
        challenges = _challenges.value,
        clearances = Clearances.hosts.keys.sorted(),
    )

    fun stop() = runBlocking { lock.withLock { browser?.let(::shutdown) } }

    // ---- tabs --------------------------------------------------------------------------------------------------

    /** Runs [block] with a fresh tab, starting Chromium if needed. [visible] switches to a visible window first. */
    suspend fun <T> withTab(visible: Boolean = false, block: suspend (Tab) -> T): T {
        active.incrementAndGet()
        lastUsed = System.currentTimeMillis()
        try {
            val b = ensure(visible)
            val tab = openTab(b)
            try {
                return block(tab)
            } finally {
                withContext(NonCancellable) {
                    b.tabs.remove(tab.sessionId)
                    runCatching { b.conn.send("Target.closeTarget", buildJsonObject { put("targetId", tab.targetId) }, timeoutMs = 5000) }
                }
            }
        } finally {
            active.decrementAndGet()
            lastUsed = System.currentTimeMillis()
        }
    }

    /** Open tabs (not counting their iframe and worker sessions); 0 when Chromium isn't running. */
    fun openTabs(): Int = browser?.tabs?.count { (session, tab) -> session == tab.sessionId } ?: 0

    /** Chromium's user agent for pages (what a WebView reports as `userAgentString`), without starting it. */
    fun pageUserAgent(): String = WebViewResolver.webViewUserAgent ?: executable()?.let(::userAgent)
        ?: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private suspend fun openTab(b: Browser): Tab {
        val targetId = b.conn.send("Target.createTarget", buildJsonObject { put("url", "about:blank") })["targetId"]!!.jsonPrimitive.content
        val sessionId = b.conn.send("Target.attachToTarget", buildJsonObject {
            put("targetId", targetId)
            put("flatten", true)
        })["sessionId"]!!.jsonPrimitive.content
        val tab = Tab(b.conn, targetId, sessionId)
        b.tabs[sessionId] = tab
        tab.send("Page.enable")
        tab.send("Network.enable")
        return tab
    }

    private fun route(b: Browser, e: CdpEvent) {
        when {
            e.method == CdpConnection.DISCONNECTED -> b.tabs.values.forEach { it.events.close() }
            e.method == "Target.detachedFromTarget" -> e.params["sessionId"]?.jsonPrimitive?.content?.let { id ->
                // A child session (iframe, worker) going away only drops its route; the tab itself closes its channel.
                val tab = b.tabs.remove(id)
                if (tab?.sessionId == id) tab.events.close()
            }
            else -> {
                val tab = e.sessionId?.let { b.tabs[it] } ?: return
                // Sessions a tab auto-attaches (Target.setAutoAttach) route to the same tab, tagged with their own id.
                if (e.method == "Target.attachedToTarget") e.params["sessionId"]?.jsonPrimitive?.content?.let { b.tabs[it] = tab }
                tab.events.trySend(e)
            }
        }
    }

    // ---- process ---------------------------------------------------------------------------------------------

    private suspend fun ensure(visible: Boolean): Browser = lock.withLock {
        val current = browser
        if (current != null && current.alive && (!visible || current.mode == Mode.HEADED)) return current
        current?.let(::shutdown)
        val mode = if (visible) Mode.HEADED else options.mode
        try {
            launch(mode).also {
                browser = it
                lastError = null
            }
        } catch (e: Exception) {
            lastError = e.message
            throw e as? ChromiumUnavailableException ?: ChromiumUnavailableException("Chromium failed to start: ${e.message}")
        }
    }

    private suspend fun launch(mode: Mode): Browser = withContext(Dispatchers.IO) {
        val o = options
        val exe = executable() ?: throw ChromiumUnavailableException(
            "Chromium isn't installed (looked for ${o.path}). Install it or set chromium.path in config.toml",
        )
        val profile = File(o.dataDir, "chromium-profile").apply { mkdirs() }
        killOrphan(profile)
        val ua = userAgent(exe)
        // A fixed port, not 0: Turnstile (GoPlay) never passes when Chromium picks its own debugging port.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        var xvfb: Process? = null
        val env = mutableMapOf<String, String>()
        val args = mutableListOf(
            exe,
            "--remote-debugging-address=127.0.0.1",
            "--remote-debugging-port=$port",
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--password-store=basic",
            "--disable-blink-features=AutomationControlled",
            "--disable-features=Translate,MediaRouter",
            "--mute-audio",
            "--user-agent=$ua",
            "--window-size=1100,850",
        )
        when (mode) {
            // The full browser drawing to no display. Turnstile stalls on its checkbox under `--headless` (the
            // separate headless shell), and passes on its own here.
            Mode.HEADLESS -> args += "--ozone-platform=headless"
            Mode.XVFB -> {
                val (process, display) = startXvfb()
                xvfb = process
                env["DISPLAY"] = display
                args += "--ozone-platform=x11"
            }
            Mode.HEADED -> if (System.getenv("WAYLAND_DISPLAY").isNullOrBlank() && System.getenv("DISPLAY").isNullOrBlank()) {
                throw ChromiumUnavailableException("No display for a visible browser window (DISPLAY/WAYLAND_DISPLAY unset)")
            }
        }
        args += "about:blank"

        log.info("Starting Chromium ($exe, ${mode.id})")
        if (File("/.dockerenv").exists() && System.getenv("TZ").isNullOrBlank() && java.time.ZoneId.systemDefault().id in UTC_IDS) {
            log.warning("Chromium runs in the UTC timezone. Cloudflare rejects a browser whose timezone doesn't match its IP's location: set the TZ environment variable on the container (e.g. TZ=Asia/Manila)")
        }
        val pb = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(File(o.dataDir, "chromium.log"))
        pb.environment().putAll(env)
        if (mode == Mode.XVFB) pb.environment().remove("WAYLAND_DISPLAY")
        val process = pb.start()
        try {
            // Cold browser startup can be slow on shared CI hosts, even after DevTools starts listening.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var endpoint: String? = null
            while (endpoint == null) {
                if (!process.isAlive) throw ChromiumUnavailableException("Chromium exited at startup: ${logTail()}")
                if (System.nanoTime() > deadline) throw ChromiumUnavailableException("Chromium's debugging endpoint wasn't ready within 60 seconds: ${logTail()}")
                endpoint = debuggerUrl(port) ?: run { delay(100); null }
            }
            val conn = CdpConnection.connect(endpoint)
            val b = Browser(process, xvfb, conn, mode)
            conn.onEvent { route(b, it) }
            Clearances.userAgent = ua
            WebViewResolver.webViewUserAgent = ua
            b
        } catch (e: Exception) {
            destroy(process)
            xvfb?.let(::destroy)
            throw e
        }
    }

    private fun shutdown(b: Browser) {
        if (browser === b) browser = null
        log.info("Stopping Chromium")
        runCatching { runBlocking { b.conn.send("Browser.close", timeoutMs = 3000) } }
        b.conn.close()
        destroy(b.process)
        b.xvfb?.let(::destroy)
    }

    private fun destroy(p: Process) {
        val children = p.toHandle().descendants().toList()
        p.destroy()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    /** A Chromium left over from a crashed server still holds the profile; a new one would just hand off to it. */
    private fun killOrphan(profile: File) {
        val lock = File(profile, "SingletonLock").toPath()
        val target = runCatching { Files.readSymbolicLink(lock).toString() }.getOrNull() ?: return
        val pid = target.substringAfterLast('-').toLongOrNull() ?: return
        // The lock is "<hostname>-<pid>". One from another host is stale: a recreated container (new hostname, same
        // data volume) would otherwise make Chromium ask whether to take the profile over, with no display to ask on.
        val host = target.substringBeforeLast('-')
        if (host != localHostName()) {
            log.info("Removing a stale Chromium profile lock from host $host")
            listOf("SingletonLock", "SingletonCookie", "SingletonSocket").forEach { runCatching { Files.deleteIfExists(File(profile, it).toPath()) } }
            return
        }
        ProcessHandle.of(pid).filter { h -> h.isAlive && commandLine(h).contains(profile.absolutePath) }
            .ifPresent { h ->
                log.info("Stopping a leftover Chromium (pid $pid) that holds the profile")
                val children = h.descendants().toList()
                children.forEach { it.destroy() }
                h.destroy()
                runCatching { h.onExit().get(5, TimeUnit.SECONDS) }.onFailure { h.destroyForcibly().also { h.onExit().get(5, TimeUnit.SECONDS) } }
                children.filter { it.isAlive }.forEach { it.destroyForcibly() }
            }
    }

    private fun localHostName(): String =
        runCatching { File("/proc/sys/kernel/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("")

    /** Chromium rewrites its process title, which leaves Java's `ProcessHandle.info()` without its arguments. */
    private fun commandLine(h: ProcessHandle): String =
        runCatching { File("/proc/${h.pid()}/cmdline").readText().replace('\u0000', ' ') }.getOrNull()
            ?: h.info().commandLine().orElse("")

    private suspend fun startXvfb(): Pair<Process, String> {
        val exe = which("Xvfb") ?: throw ChromiumUnavailableException("Xvfb isn't installed: install it or pick another browser mode")
        val n = (99..199).first { !File("/tmp/.X11-unix/X$it").exists() && !File("/tmp/.X$it-lock").exists() }
        val process = ProcessBuilder(exe, ":$n", "-screen", "0", "1280x900x24", "-nolisten", "tcp")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val socket = File("/tmp/.X11-unix/X$n")
        repeat(50) {
            if (socket.exists()) return process to ":$n"
            if (!process.isAlive) throw ChromiumUnavailableException("Xvfb exited at startup")
            delay(100)
        }
        destroy(process)
        throw ChromiumUnavailableException("Xvfb didn't start")
    }

    fun executable(): String? {
        val configured = options.path
        if ('/' in configured) return configured.takeIf { File(it).canExecute() }
        return (listOf(configured) + FALLBACKS).firstNotNullOfOrNull(::which)
    }

    /** Chrome's reduced user agent for this build, without "HeadlessChrome", so every mode looks the same. */
    private fun userAgent(exe: String): String = userAgents.getOrPut(exe) {
        val version = runCatching {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            p.waitFor(10, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val major = Regex("""(\d+)\.\d+\.\d+""").find(version)?.groupValues?.get(1) ?: "149"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    private fun logTail() = runCatching { File(options.dataDir, "chromium.log").readLines().takeLast(3).joinToString(" | ") }.getOrDefault("")

    /** The browser's WebSocket once its debugging [port] answers (with a fixed port it writes no DevToolsActivePort). */
    private fun debuggerUrl(port: Int): String? = runCatching {
        val c = URI("http://127.0.0.1:$port/json/version").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 2000
        val body = c.inputStream.use { it.bufferedReader().readText() }
        Json.parseToJsonElement(body).jsonObject["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun which(name: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath

    private val FALLBACKS = listOf("chromium", "chromium-browser", "google-chrome-stable", "google-chrome")

    // ---- cookies -----------------------------------------------------------------------------------------------

    /** Cookies Chromium would send to [url]. Starts Chromium when [start] is set; otherwise empty if it isn't running. */
    suspend fun cookies(url: String, start: Boolean = false): Map<String, String> {
        val b = if (start) ensure(false) else browser?.takeIf { it.alive } ?: return emptyMap()
        val uri = URI(url)
        val all = b.conn.send("Storage.getCookies")["cookies"]?.jsonArray.orEmpty()
        return all.map { it.jsonObject }.filter { matches(it, uri) }.associate {
            it["name"]!!.jsonPrimitive.content to it["value"]!!.jsonPrimitive.content
        }
    }

    /** Applies a `Set-Cookie`-style [header] for [url] (as `CookieManager.setCookie` does). */
    suspend fun setCookie(url: String, header: String) {
        val b = ensure(false)
        val parts = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val (name, value) = parts.firstOrNull()?.split('=', limit = 2)?.takeIf { it.size == 2 } ?: return
        val attrs = parts.drop(1).associate { p -> p.substringBefore('=').trim().lowercase() to p.substringAfter('=', "").trim() }
        val cookie = buildJsonObject {
            put("name", name.trim())
            put("value", value.trim())
            put("url", url)
            attrs["domain"]?.takeIf { it.isNotEmpty() }?.let { put("domain", it) }
            put("path", attrs["path"]?.ifEmpty { null } ?: "/")
            if ("secure" in attrs) put("secure", true)
            if ("httponly" in attrs) put("httpOnly", true)
            val maxAge = attrs["max-age"]?.toLongOrNull()
            if (maxAge != null) put("expires", if (maxAge <= 0) 1.0 else System.currentTimeMillis() / 1000.0 + maxAge)
        }
        b.conn.send("Storage.setCookies", buildJsonObject { put("cookies", buildJsonArray { add(cookie) }) })
    }

    private fun matches(cookie: JsonObject, uri: URI): Boolean {
        val host = uri.host ?: return false
        val raw = cookie["domain"]?.jsonPrimitive?.contentOrNull ?: return false
        val domain = raw.removePrefix(".")
        val domainOk = host.equals(domain, true) || (raw.startsWith(".") && host.endsWith(".$domain", true))
        val path = cookie["path"]?.jsonPrimitive?.contentOrNull ?: "/"
        val expired = cookie["expires"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?.let { it > 0 && it < System.currentTimeMillis() / 1000.0 } ?: false
        return domainOk && uri.path.ifEmpty { "/" }.startsWith(path) && !expired
    }

    // ---- resolving pages (WebViewResolver) ---------------------------------------------------------------------

    /**
     * Loads [request] in a tab and watches the requests the page makes: returns the first matching [interceptUrl]
     * and every one matching [additionalUrls], as the app's WebView resolver does.
     */
    suspend fun resolve(
        request: Request,
        interceptUrl: Regex,
        additionalUrls: List<Regex>,
        userAgent: String?,
        script: String?,
        scriptCallback: ((String) -> Unit)?,
        timeoutMs: Long,
        callback: (Request) -> Boolean,
    ): Pair<Request?, List<Request>> = withTab { tab ->
        userAgent?.let { tab.send("Network.setUserAgentOverride", buildJsonObject { put("userAgent", it) }) }
        val skip = setOf("user-agent", "cookie", "referer", "host")
        val extra = request.headers.filter { it.first.lowercase() !in skip }
        if (extra.isNotEmpty()) tab.send("Network.setExtraHTTPHeaders", buildJsonObject {
            putJsonObject("headers") { extra.forEach { (k, v) -> put(k, v) } }
        })
        request.header("Cookie")?.let { header ->
            val cookies = header.split(';').mapNotNull { c -> c.split('=', limit = 2).takeIf { it.size == 2 } }
            if (cookies.isNotEmpty()) tab.send("Network.setCookies", buildJsonObject {
                put("cookies", buildJsonArray {
                    cookies.forEach { (k, v) -> add(buildJsonObject { put("name", k.trim()); put("value", v.trim()); put("url", request.url.toString()) }) }
                })
            })
        }
        tab.navigate(request.url.toString(), request.header("Referer"))

        var fixed: Request? = null
        val seen = mutableListOf<Request>()
        var done = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done) {
            val e = tab.next(deadline - System.currentTimeMillis()) ?: break
            when (e.method) {
                Tab.CLOSED.method -> break
                "Network.requestWillBeSent" -> {
                    val r = e.params["request"]?.jsonObject?.let(::toRequest) ?: continue
                    val url = r.url.toString()
                    if (interceptUrl.containsMatchIn(url)) {
                        fixed = r
                        runCatching { callback(r) }
                        done = true
                    }
                    if (additionalUrls.any { it.containsMatchIn(url) }) {
                        seen += r
                        if (runCatching { callback(r) }.getOrDefault(false)) done = true
                    }
                }
                "Page.loadEventFired" -> if (script != null) {
                    val value = runCatching { tab.evaluate(script) }.getOrNull()
                    runCatching { scriptCallback?.invoke(value?.toString() ?: "null") }
                }
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        if (!done && options.mode != Mode.HEADED) addChallenge("page", request.url.toString())
        fixed to seen
    }

    private fun toRequest(r: JsonObject): Request? = runCatching {
        val url = r["url"]!!.jsonPrimitive.content
        if (!url.startsWith("http")) return null
        val method = r["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = Headers.Builder().apply {
            r["headers"]?.jsonObject?.forEach { (k, v) -> runCatching { add(k, v.jsonPrimitive.content) } }
        }.build()
        val body = r["postData"]?.jsonPrimitive?.contentOrNull?.toRequestBody()
            ?: if (method in BODY_METHODS) ByteArray(0).toRequestBody() else null
        Request.Builder().url(url).headers(headers).method(method, body).build()
    }.getOrNull()

    private val UTC_IDS = setOf("UTC", "Etc/UTC", "Z", "GMT", "Etc/GMT")

    private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

    // ---- Cloudflare ------------------------------------------------------------------------------------------

    /**
     * Cookies that pass the Cloudflare check on [url]'s host: a clearance another request just earned, a background
     * solve in Chromium, or (failing that) a manual solve from the UI while the request waits. Null if none came.
     * [stale] is a clearance the site just refused.
     *
     * There is one background solve per host, and it outlives the request that started it: plugins give a source
     * only seconds, and a solve cancelled halfway would leave nothing for the next request.
     */
    suspend fun solveCloudflare(url: String, stale: Map<String, String>?, canceled: () -> Boolean): Map<String, String>? {
        val host = URI(url).host
        fresh(host, stale)?.let { return it }
        if (stale != null && Clearances.hosts[host] == stale) Clearances.hosts.remove(host)
        val solve = solves.compute(host) { _, running ->
            running?.takeIf { it.isActive } ?: scope.async { backgroundSolve(url, host) }
        }!!
        val first = poll(options.solveTimeoutMs + 10_000, canceled) {
            fresh(host, stale) ?: NO_COOKIES.takeIf { solve.isCompleted }
        } ?: return null // the caller gave up; the solve carries on
        if (first.isNotEmpty()) return first
        // The background solve failed and listed the check: wait for someone to pass it by hand.
        return poll(options.manualWaitMs, canceled) { fresh(host, stale) }
    }

    private suspend fun backgroundSolve(url: String, host: String): Map<String, String>? {
        log.info("Solving the Cloudflare check for $host in Chromium")
        val start = System.currentTimeMillis()
        val solved = try {
            withTab { tab ->
                // A solve only runs after the site refused what we had, so a clearance still in Chromium's jar is
                // spent; without this, it would look solved at once.
                tab.send("Network.deleteCookies", buildJsonObject {
                    put("name", CLEARANCE)
                    put("url", url)
                })
                tab.navigate(url)
                poll(options.solveTimeoutMs, { false }) { cookies(url).takeIf { CLEARANCE in it } }
            }
        } catch (e: ChromiumUnavailableException) {
            log.warning("Can't solve the Cloudflare check for $host: ${e.message}")
            return null
        } catch (e: Exception) {
            // Without this the Deferred just fails: nothing is logged and no check is listed, while the request that
            // started it still waits out manualWaitMs for a manual solve nobody is offered.
            log.warning("The Cloudflare solve for $host failed: $e")
            null
        } finally {
            solves.remove(host)
        }
        if (solved != null) {
            log.info("Passed the Cloudflare check for $host in ${(System.currentTimeMillis() - start) / 1000} s")
            Clearances.hosts[host] = solved
            removeChallenges("cloudflare", host)
        } else {
            log.info("The Cloudflare check for $host needs a manual solve")
            addChallenge("cloudflare", url)
        }
        return solved
    }

    private fun fresh(host: String, stale: Map<String, String>?) =
        Clearances.hosts[host]?.takeIf { it != stale && CLEARANCE in it }

    private val solves = ConcurrentHashMap<String, Deferred<Map<String, String>?>>()
    private val NO_COOKIES = emptyMap<String, String>()

    private suspend fun <T> poll(timeoutMs: Long, canceled: () -> Boolean, check: suspend () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !canceled()) {
            check()?.let { return it }
            delay(500)
        }
        return null
    }

    // ---- manual checks -----------------------------------------------------------------------------------------

    private fun addChallenge(kind: String, url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        _challenges.update { list ->
            if (list.any { it.kind == kind && it.host == host }) list
            else list + Challenge("c" + ids.incrementAndGet(), kind, host, url, System.currentTimeMillis())
        }
    }

    private fun removeChallenges(kind: String, host: String) {
        _challenges.update { list -> list.filterNot { it.kind == kind && it.host == host } }
    }

    private fun expireChallenges() {
        val cutoff = System.currentTimeMillis() - 30 * 60_000L
        _challenges.update { list -> list.filter { it.solving || it.since > cutoff } }
    }

    fun dismiss(id: String) {
        _challenges.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Opens [id]'s page in a visible window (switching Chromium to one if it runs headless). A Cloudflare check is
     * done once its clearance cookie appears; a page check once the tab is closed. Returns without waiting.
     */
    fun solveManually(id: String) {
        val challenge = _challenges.value.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No such check")
        if (challenge.solving) return
        if (System.getenv("WAYLAND_DISPLAY").isNullOrBlank() && System.getenv("DISPLAY").isNullOrBlank()) {
            throw IllegalArgumentException("A manual check needs a display on the server; this server has no DISPLAY or WAYLAND_DISPLAY")
        }
        _challenges.update { list -> list.map { if (it.id == id) it.copy(solving = true) else it } }
        scope.launch {
            var solved = false
            try {
                withTab(visible = true) { tab ->
                    tab.navigate(challenge.url)
                    runCatching { tab.send("Page.bringToFront") }
                    val deadline = System.currentTimeMillis() + 10 * 60_000L
                    while (System.currentTimeMillis() < deadline) {
                        if (challenge.kind == "cloudflare") {
                            cookies(challenge.url).takeIf { CLEARANCE in it }?.let {
                                Clearances.hosts[challenge.host] = it
                                solved = true
                                return@withTab
                            }
                        }
                        if (tab.next(1000) === Tab.CLOSED) {
                            solved = challenge.kind == "page"
                            return@withTab
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
                log.warning("Manual check for ${challenge.host} failed: ${e.message}")
            } finally {
                if (solved) removeChallenges(challenge.kind, challenge.host)
                else _challenges.update { list -> list.map { if (it.id == id) it.copy(solving = false) else it } }
            }
        }
    }

    /** Blocking forms for the plugin-facing classes (CookieManager, interceptors). */
    fun cookiesBlocking(url: String, start: Boolean) = runBlocking { cookies(url, start) }
    fun setCookieBlocking(url: String, header: String) = runBlocking { setCookie(url, header) }

    const val CLEARANCE = "cf_clearance"
}
