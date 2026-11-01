package desktop

import com.lagradost.cloudstream3.app
import desktop.plugins.PluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class HostLimitTest {
    @Test
    fun pluginsMakeAtMostTwoConcurrentRequestsToOneHost() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val before = app.baseClient.dispatcher.maxRequestsPerHost
        PluginRuntime.limitRequestsPerHost(2)
        try {
            LocalServer { ex ->
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                Thread.sleep(150)
                active.decrementAndGet()
                ex.respond(200, "ok")
            }.use { server ->
                val bodies = runBlocking {
                    // The way plugins call it: NiceHttp's suspend API, some with a per-request interceptor/timeout
                    // (which builds a derived client).
                    (1..8).map { i ->
                        async(Dispatchers.IO) {
                            if (i % 2 == 0) app.get("${server.url}/$i").text
                            else app.get("${server.url}/$i", timeout = 30L, interceptor = { it.proceed(it.request()) }).text
                        }
                    }.awaitAll()
                }
                assertEquals(List(8) { "ok" }, bodies)
            }
        } finally {
            PluginRuntime.limitRequestsPerHost(before)
        }
        assertEquals(2, peak.get())
    }
}
