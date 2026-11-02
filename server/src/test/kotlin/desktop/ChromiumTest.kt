package desktop

import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import desktop.cdp.Chromium
import desktop.cdp.Clearances
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real headless Chromium against local pages. Skipped where Chromium isn't installed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChromiumTest {
    private val dataDir = createTempDirectory().toFile()

    @BeforeAll
    fun start() {
        Chromium.configure(Chromium.Options(mode = Chromium.Mode.HEADLESS, dataDir = dataDir, solveTimeoutMs = 20_000, manualWaitMs = 500))
        assumeTrue(Chromium.executable() != null, "Chromium isn't installed")
    }

    @AfterAll
    fun stop() {
        Chromium.stop()
    }

    @Test
    fun webViewResolverReturnsTheRequestsThePageMakes() {
        val userAgents = CopyOnWriteArrayList<String>()
        LocalServer { ex ->
            userAgents += ex.requestHeaders.getFirst("User-Agent")
            when (ex.requestURI.path) {
                "/" -> ex.respond(200, """<script>document.cookie = "seen=1; path=/"; fetch("/api/data?id=7")</script>""")
                else -> ex.respond(200, "{}", "application/json")
            }
        }.use { server ->
            val (request, extra) = runBlocking {
                WebViewResolver(Regex("/api/data"), additionalUrls = listOf(Regex("127\\.0\\.0\\.1")), userAgent = null, timeout = 20_000)
                    .resolveUsingWebView("${server.url}/")
            }
            assertEquals("${server.url}/api/data?id=7", request?.url.toString())
            assertTrue(extra.any { it.url.toString() == "${server.url}/" }, "the page itself matched additionalUrls")
            assertContains(CookieManager.getInstance().getCookie(server.url).orEmpty(), "seen=1")
            assertTrue(userAgents.none { "Headless" in it }, "headless Chromium doesn't announce itself: $userAgents")
            assertEquals(WebViewResolver.webViewUserAgent, userAgents.first())
        }
    }

    @Test
    fun aPageThatNeverFinishesIsOfferedAsAManualCheck() {
        LocalServer { ex -> ex.respond(200, "<p>waiting for a human</p>") }.use { server ->
            val (request, _) = runBlocking {
                WebViewResolver(Regex("never-matches"), userAgent = null, timeout = 1500).resolveUsingWebView("${server.url}/gate")
            }
            assertNull(request)
            val check = Chromium.challenges.value.single { it.kind == "page" }
            assertEquals("127.0.0.1", check.host)
            Chromium.dismiss(check.id)
        }
    }

    /**
     * A fake Cloudflare: a 403 from "cloudflare" whose page sets `cf_clearance` with JavaScript. The killer must
     * solve it in Chromium, retry with the cookie and Chromium's user agent, and replay it next time without a solve.
     */
    @Test
    fun cloudflareKillerSolvesInChromiumAndReplaysTheClearance() {
        val challenges = AtomicInteger()
        LocalServer { ex ->
            val cookie = ex.requestHeaders.getFirst("Cookie").orEmpty()
            val ua = ex.requestHeaders.getFirst("User-Agent").orEmpty()
            if ("cf_clearance=ok" in cookie && ua == WebViewResolver.webViewUserAgent) {
                ex.respond(200, "welcome")
            } else {
                challenges.incrementAndGet()
                ex.respond(403, """<title>Just a moment...</title><script>document.cookie = "cf_clearance=ok; path=/"</script>""",
                    headers = mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"))
            }
        }.use { server ->
            val host = "127.0.0.1"
            Clearances.hosts.remove(host)
            val client = app.baseClient.newBuilder().addInterceptor(CloudflareKiller()).build()
            fun get() = client.newCall(Request.Builder().url("${server.url}/page").build()).execute().use { it.code to it.body.string() }

            assertEquals(200 to "welcome", get())
            assertEquals("ok", Clearances.hosts[host]?.get("cf_clearance"))
            val afterSolve = challenges.get()
            assertEquals(200 to "welcome", get())
            assertEquals(afterSolve, challenges.get(), "the saved clearance is replayed without a new challenge")
            assertContains(File(dataDir, "clearances.json").readText(), "cf_clearance", message = "saved for the next start")
        }
    }

    /** Plugins give sources seconds; a solve the caller abandons still finishes and saves its clearance. */
    @Test
    fun aSolveOutlivesTheRequestThatStartedIt() {
        LocalServer { ex ->
            if ("cf_clearance=late" in ex.requestHeaders.getFirst("Cookie").orEmpty()) ex.respond(200, "welcome")
            else ex.respond(403, """<script>setTimeout(() => { document.cookie = "cf_clearance=late; path=/" }, 2500)</script>""",
                headers = mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"))
        }.use { server ->
            Clearances.hosts.remove("127.0.0.1")
            val impatient = app.baseClient.newBuilder().addInterceptor(CloudflareKiller())
                .callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
            runCatching { impatient.newCall(Request.Builder().url("${server.url}/a").build()).execute().close() }
            assertNull(Clearances.hosts["127.0.0.1"], "not solved yet when the caller gave up")
            Thread.sleep(5000)
            assertEquals("late", Clearances.hosts["127.0.0.1"]?.get("cf_clearance"))
        }
    }

    @Test
    fun anUnpassableCheckReturnsTheChallengeAndIsListed() {
        val requests = AtomicInteger()
        LocalServer { ex ->
            if (ex.requestHeaders.getFirst("X-Test-Caller") == "yes") requests.incrementAndGet()
            ex.respond(503, "<p>checking your browser</p>", headers = mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"))
        }.use { server ->
            Clearances.hosts.remove("127.0.0.1")
            val options = Chromium.options
            Chromium.configure(options.copy(solveTimeoutMs = 1500))
            try {
                val client = app.baseClient.newBuilder().addInterceptor(CloudflareKiller()).build()
                client.newCall(Request.Builder().url("${server.url}/x").header("X-Test-Caller", "yes").build()).execute().use {
                    assertEquals(503, it.code)
                    assertEquals("<p>checking your browser</p>", it.body.string())
                }
                assertEquals(1, requests.get(), "a failed solve must not replay the caller request")
                val check = Chromium.challenges.value.singleOrNull { it.kind == "cloudflare" }
                assertNotNull(check)
                assertFalse(check.solving)
                Chromium.dismiss(check.id)
            } finally {
                Chromium.configure(options)
            }
        }
    }

    @Test
    fun aChromiumLeftOverFromACrashedServerIsStopped() {
        Chromium.stop()
        val profile = File(dataDir, "chromium-profile")
        val orphan = ProcessBuilder(Chromium.executable(), "--headless", "--remote-debugging-port=0", "--user-data-dir=${profile.absolutePath}", "about:blank")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val lock = File(profile, "SingletonLock").toPath()
            val deadline = System.currentTimeMillis() + 10_000
            while (!java.nio.file.Files.isSymbolicLink(lock) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(orphan.isAlive, "the orphan holds the profile")
            assertEquals("about:blank", runBlocking { Chromium.withTab { it.evaluate("location.href")?.toString()?.trim('"') } })
            assertFalse(orphan.isAlive, "the orphan was stopped")
        } finally {
            orphan.destroyForcibly()
        }
    }

    /** GoPlay's own solve (GoPlaySession) against the live site, from a fresh profile. Opt in with LIVE_GOPLAY=1. */
    @Test
    fun goPlayTurnstilePassesInTheBackground() {
        assumeTrue(System.getenv("LIVE_GOPLAY") == "1", "live test; set LIVE_GOPLAY=1")
        val origin = "https://goplay.su"
        val started = System.currentTimeMillis()
        runBlocking {
            WebViewResolver(Regex(".^"), additionalUrls = listOf(Regex(".")), userAgent = null, timeout = 60_000)
                .resolveUsingWebView("$origin/") { "d=" in CookieManager.getInstance().getCookie(origin).orEmpty() }
        }
        val cookies = CookieManager.getInstance().getCookie(origin).orEmpty()
        assertContains(cookies, "d=", message = "GoPlay's session cookie after ${System.currentTimeMillis() - started} ms")
        val page = runBlocking { app.get("$origin/", headers = mapOf("User-Agent" to WebViewResolver.webViewUserAgent!!), cookies = CloudflareKiller.parseCookieMap(cookies)) }
        assertFalse("form-ts" in page.text || "Generating Access Token" in page.text, "plain HTTP with the cookies gets past the gate")
    }
}
