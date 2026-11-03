package desktop

import com.lagradost.cloudstream3.SubtitleFile
import desktop.links.ResolvedSubtitle
import desktop.links.readSubtitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientSubtitleTest {
    @Test
    fun renewsAndSharesSingleUseSubtitles() = runBlocking {
        val hits = AtomicInteger()
        val text = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        LocalServer { ex ->
            if (ex.requestURI.path != "/fresh.vtt" || hits.incrementAndGet() != 1) ex.respond(403, "")
            else ex.respond(200, text, "text/vtt")
        }.use { upstream ->
            var interceptor: Interceptor? = null
            val sub = ResolvedSubtitle("s1", SubtitleFile("English", "${upstream.url}/spent.vtt")) { interceptor }
            // Video links may arrive after the subtitle callback.
            interceptor = Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().url("${upstream.url}/fresh.vtt").build())
            }
            val client = OkHttpClient()
            val results = List(3) { async { readSubtitle(sub, client) } }.awaitAll()
            results.forEach { assertEquals(text, it.second.decodeToString()) }
            assertEquals(1, hits.get())
        }
    }

    @Test
    fun doesNotCacheAFailedSubtitle() = runBlocking {
        val hits = AtomicInteger()
        LocalServer { ex ->
            if (hits.incrementAndGet() == 1) ex.respond(403, "")
            else ex.respond(200, "WEBVTT\n", "text/vtt")
        }.use { upstream ->
            val sub = ResolvedSubtitle("s1", SubtitleFile("English", "${upstream.url}/sub.vtt"))
            val client = OkHttpClient()
            assertFailsWith<IllegalStateException> { readSubtitle(sub, client) }
            assertEquals("WEBVTT\n", readSubtitle(sub, client).second.decodeToString())
            assertEquals(2, hits.get())
        }
    }
}
