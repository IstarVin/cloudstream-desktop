package desktop

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import desktop.links.LinkHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkHeadersTest {
    private fun link(referer: String, headers: Map<String, String>) = runBlocking {
        newExtractorLink("S", "S", "https://cdn.example/a.m3u8", ExtractorLinkType.M3U8) {
            this.referer = referer
            this.headers = headers
        }
    }

    @Test
    fun splitsUserAgentAndRefererFromTheRest() {
        val h = LinkHeaders.of(link("", mapOf("user-agent" to "UA", "referer" to "https://site/", "X-Token" to "t")))
        assertEquals("UA", h.userAgent)
        assertEquals("https://site/", h.referer)
        assertEquals(mapOf("X-Token" to "t"), h.extra)
        assertEquals(mapOf("X-Token" to "t", "Referer" to "https://site/", "User-Agent" to "UA"), h.all)
    }

    @Test
    fun theLinksRefererWinsAndTheUserAgentDefaults() {
        val h = LinkHeaders.of(link("https://link/", mapOf("Referer" to "https://header/")))
        assertEquals("https://link/", h.referer)
        assertEquals(USER_AGENT, h.userAgent)
        assertEquals(emptyMap(), h.extra)
    }
}
