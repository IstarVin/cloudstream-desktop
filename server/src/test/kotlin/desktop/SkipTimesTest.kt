package desktop

import desktop.db.Database
import desktop.player.SkipRange
import desktop.player.SkipRule
import desktop.player.SkipTimes
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipTimesTest {
    private val found = """{"found":true,"results":[
        {"interval":{"startTime":13.9,"endTime":103.8},"skipType":"op","skipId":"a","episodeLength":1430.7},
        {"interval":{"startTime":20.0,"endTime":110.0},"skipType":"op","skipId":"b","episodeLength":1300.0},
        {"interval":{"startTime":1340.0,"endTime":1430.0},"skipType":"mixed-ed","skipId":"c","episodeLength":1431.0},
        {"interval":{"startTime":0.0,"endTime":3.0},"skipType":"recap","skipId":"d","episodeLength":0}
    ],"message":"Successfully found skip times","statusCode":200}"""

    @Test
    fun keepsTheBestRangePerTypeForThisCutOfTheEpisode() {
        val ranges = SkipTimes.matching(found, 1431.0)
        assertEquals(listOf(SkipRange("op", 13.9, 103.8), SkipRange("ed", 1340.0, 1430.0)), ranges,
            "the 1300 s cut's opening and the 3 s recap are dropped; mixed-ed counts as an ending")
        assertEquals(1420.0, SkipTimes.matching(found, 1420.0).last().end, "clamped to the file")
        assertEquals(listOf(SkipRange("op", 20.0, 110.0)), SkipTimes.matching(found, 1300.0), "the other cut")
        assertTrue(SkipTimes.matching("""{"found":false,"results":[]}""", 1431.0).isEmpty())
        assertTrue(SkipTimes.matching("not json", 1431.0).isEmpty())
    }

    @Test
    fun cachesAnswersIncludingNotFound() = runBlocking {
        val calls = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            calls += url.encodedPath
            val body = if (url.encodedPath.endsWith("/21/1")) found else """{"found":false,"results":[],"statusCode":404}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (url.encodedPath.endsWith("/21/1")) 200 else 404).message("")
                .body(body.toResponseBody()).build()
        }).build()
        val skips = SkipTimes(Database(File(createTempDirectory().toFile(), "t.db")), http)

        assertEquals(2, skips.forEpisode(21, null, 1, 1431.0).size)
        assertEquals(2, skips.forEpisode(21, null, 1, 1430.0).size, "another link of the same episode")
        assertTrue(skips.forEpisode(21, null, 2, 1431.0).isEmpty())
        assertTrue(skips.forEpisode(21, null, 2, 1431.0).isEmpty())
        assertTrue(skips.forEpisode(null, null, 1, 1431.0).isEmpty(), "no ids: no lookup")
        assertEquals(listOf("/v2/skip-times/21/1", "/v2/skip-times/21/2"), calls)
    }

    @Test
    fun autoSkipsEachRangeOncePerFile() {
        val op = SkipRange("op", 10.0, 100.0)
        val ed = SkipRange("ed", 1300.0, 1400.0)
        val ranges = listOf(op, ed)
        assertNull(SkipRule.current(ranges, 5.0))
        assertEquals(op, SkipRule.current(ranges, 10.0))
        assertNull(SkipRule.current(ranges, 99.5), "the last second doesn't count, so landing on the end isn't 'inside'")

        val skipped = mutableSetOf<SkipRange>()
        assertEquals(op, SkipRule.autoSkip(ranges, 12.0, skipped))
        skipped += op
        assertNull(SkipRule.autoSkip(ranges, 30.0, skipped), "seeking back into a skipped opening leaves it alone")
        assertEquals(ed, SkipRule.autoSkip(ranges, 1350.0, skipped))
    }
}
