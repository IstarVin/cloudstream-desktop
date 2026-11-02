package desktop

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareKillerTest {
    private fun response(code: Int, body: String, type: String = "text/html", mitigated: String? = null) =
        Response.Builder().request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Server", "cloudflare").header("Content-Type", type)
            .apply { mitigated?.let { header("cf-mitigated", it) } }
            .body(body.toResponseBody(type.toMediaType())).build()

    @Test
    fun explicitChallengeSignalDoesNotDependOnStatus() {
        for (code in listOf(200, 403, 429, 503)) {
            response(code, "check", mitigated = "Challenge").use {
                assertTrue(CloudflareKiller.isChallenge(it))
            }
        }
    }

    @Test
    fun ordinaryCdnErrorsAreNotChallenges() {
        response(403, "<h1>Forbidden</h1>").use { assertFalse(CloudflareKiller.isChallenge(it)) }
        response(503, "{\"error\":\"cf-chl-unavailable\"}", "application/json").use {
            assertFalse(CloudflareKiller.isChallenge(it))
        }
    }

    @Test
    fun legacyDetectionLeavesTheBodyReadableAndBoundsInspection() {
        val html = "<script src='/cdn-cgi/challenge-platform/test'></script>"
        response(503, html).use {
            assertTrue(CloudflareKiller.isChallenge(it))
            assertEquals(html, it.body.string())
        }
        response(503, "x".repeat(65536) + html).use { assertFalse(CloudflareKiller.isChallenge(it)) }
    }

    @Test
    fun oneShotBodyIsNeverReplayed() {
        val writes = AtomicInteger()
        val requests = AtomicInteger()
        LocalServer { ex ->
            requests.incrementAndGet()
            ex.requestBody.readBytes()
            ex.respond(403, "original challenge", headers = mapOf("cf-mitigated" to "challenge"))
        }.use { server ->
            val body = object : RequestBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { writes.incrementAndGet(); sink.writeUtf8("payload") }
            }
            val client = OkHttpClient.Builder().addInterceptor(CloudflareKiller()).build()
            client.newCall(Request.Builder().url(server.url).post(body).build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("original challenge", it.body.string())
            }
            assertEquals(1, writes.get())
            assertEquals(1, requests.get())
        }
    }
}
