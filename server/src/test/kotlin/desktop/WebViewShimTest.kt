package desktop

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import desktop.cdp.Chromium
import desktop.compat.DesktopContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The android.webkit.WebView emulation on real headless Chromium, the way StreamCorner drives it: a page served from
 * shouldInterceptRequest on a real origin, a JavaScript bridge, a cross-site iframe whose requests are intercepted,
 * and blocked top-frame navigations. Skipped where Chromium isn't installed. It calls getters explicitly, as plugin
 * bytecode compiled against android.jar does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebViewShimTest {
    @BeforeAll
    fun start() {
        Chromium.configure(Chromium.Options(mode = Chromium.Mode.HEADLESS, dataDir = createTempDirectory().toFile()))
        assumeTrue(Chromium.executable() != null, "Chromium isn't installed")
    }

    @AfterAll
    fun stop() {
        Chromium.stop()
    }

    @Test
    fun mainIsItsOwnThread() = runBlocking {
        // Coroutine debug mode appends " @coroutine#n" to the name.
        assertEquals("main", withContext(Dispatchers.Main) { Thread.currentThread().name.substringBefore(' ') })
        assertEquals("main", withContext(Dispatchers.Main.immediate) { Thread.currentThread().name.substringBefore(' ') })
    }

    @Test
    fun talksToAModuleWorkerThroughEvaluateJavascript() {
        // StreamCorner's decoder: a page starts the site's module worker, the plugin posts messages with
        // evaluateJavascript, and the worker's replies come back over the JavaScript bridge.
        LocalServer { ex ->
            when (ex.requestURI.path) {
                "/worker.js" -> ex.respond(200, "self.onmessage = function (e) { fetch('/echo?v=' + e.data.v).then(function (r) { return r.text() }).then(function (t) { postMessage({requestId: e.data.requestId, echo: t}) }) }", "text/javascript")
                "/echo" -> ex.respond(200, "echo:" + ex.requestURI.query, "text/plain")
                else -> ex.respond(404, "")
            }
        }.use { site ->
            val pageUrl = "${site.url}/__cs/worker.html"
            val page = """<!doctype html><script>
                var w = new Worker("/worker.js", {type: "module"});
                w.onmessage = function (e) { CsBridge.reply(e.data.requestId | 0, JSON.stringify(e.data)) };
                CsBridge.ready();
                function csCall(id, msg) { msg.requestId = id; w.postMessage(msg) }
                </script>"""
            val ready = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<String>()
            val view = runBlocking {
                withContext(Dispatchers.Main) {
                    WebView(DesktopContext("test")).apply {
                        setWebViewClient(object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (request.getUrl().toString() == pageUrl) WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(page.toByteArray())) else null
                        })
                        addJavascriptInterface(object {
                            @JavascriptInterface fun ready() { ready.complete(Unit) }
                            @JavascriptInterface fun reply(id: Int, json: String) { reply.complete("$id $json") }
                        }, "CsBridge")
                        loadUrl(pageUrl)
                    }
                }
            }
            try {
                runBlocking {
                    withTimeout(20_000) { ready.await() }
                    withContext(Dispatchers.Main) { view.evaluateJavascript("csCall(5, {v: 42})", null) }
                    assertEquals("""5 {"requestId":5,"echo":"echo:v=42"}""", withTimeout(20_000) { reply.await() })
                }
            } finally {
                view.destroy()
            }
        }
    }

    @Test
    fun runsAPageLikeStreamCornersBridge() {
        val hits = CopyOnWriteArrayList<String>()
        // The embed is another site, so Chromium runs it out of process and its requests come from a child session.
        // It's served by the client too: Chrome blocks a 127.0.0.1 page from framing another local host.
        val embedUrl = "http://embed.example/embed"
        val embedPage = """<script>fetch("/live/index.m3u8").catch(function(){})</script>"""
        run {
            LocalServer { ex ->
                hits += ex.requestURI.path
                ex.respond(200, "real")
            }.use { site ->
                val pageUrl = "${site.url}/__cs/bridge.html"
                val replies = CompletableDeferred<Pair<Int, String>>()
                val manifest = CompletableDeferred<Pair<String, Map<String, String>>>()
                val overridden = CopyOnWriteArrayList<String>()
                val page = """<!doctype html><script>
                    fetch("/__cs/data.json").then(function(r){return r.text()}).then(function(t){CsTest.reply(CsTest.twice(3) + 1, t)});
                    setTimeout(function(){ location.href = "/elsewhere" }, 300);
                    </script><iframe src="$embedUrl"></iframe>"""

                val view = runBlocking {
                    withContext(Dispatchers.Main) {
                        WebView(DesktopContext("test")).apply {
                            getSettings().setJavaScriptEnabled(true)
                            layout(0, 0, 1280, 720)
                            setWebViewClient(object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.getUrl().toString()
                                    return when {
                                        url == pageUrl -> WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(page.toByteArray()))
                                        url == embedUrl -> WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(embedPage.toByteArray()))
                                        url.endsWith("/__cs/data.json") -> WebResourceResponse("application/json", "utf-8", ByteArrayInputStream("""{"ok":1}""".toByteArray()))
                                        url.endsWith(".m3u8") -> {
                                            manifest.complete(url to request.getRequestHeaders())
                                            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                        }
                                        else -> null
                                    }
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    overridden += request.getUrl().toString()
                                    return request.isForMainFrame() && request.getUrl().toString() != pageUrl
                                }
                            })
                            addJavascriptInterface(object {
                                @JavascriptInterface fun reply(id: Int, json: String) {
                                    replies.complete(id to json)
                                }

                                @JavascriptInterface fun twice(n: Int): Int = n * 2
                            }, "CsTest")
                            loadUrl(pageUrl)
                        }
                    }
                }
                try {
                    runBlocking {
                        assertEquals(7 to """{"ok":1}""", withTimeout(20_000) { replies.await() }, "intercepted fetch, and JS bridge calls with return values")
                        val (m3u8, headers) = withTimeout(20_000) { manifest.await() }
                        assertEquals("http://embed.example/live/index.m3u8", m3u8, "a request from the out-of-process iframe")
                        assertTrue(headers.keys.any { it.equals("Referer", true) }, "the iframe's request headers: $headers")

                        val result = CompletableDeferred<String>()
                        view.evaluateJavascript("1 + 1") { result.complete(it) }
                        assertEquals("2", withTimeout(10_000) { result.await() })
                        delay(1000)
                    }
                    assertTrue(overridden.any { it.endsWith("/elsewhere") }, "the page's navigation was offered: $overridden")
                    assertFalse("/elsewhere" in hits, "and blocked: $hits")
                    assertFalse("/__cs/bridge.html" in hits, "the page itself never hit the network")
                    assertTrue("/favicon.ico" in hits, "requests the client passes on reach the network")
                } finally {
                    view.destroy()
                }
                runBlocking {
                    withTimeout(10_000) { while (Chromium.openTabs() > 0) delay(100) }
                }
            }
        }
    }
}
