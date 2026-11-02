package desktop

import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import desktop.links.ClearKey
import desktop.links.StreamProxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClearKeyTest {
    @Test
    fun readsHexAndJwkKeys() {
        val hex = "76a6c65c5ea762046bd749a2e632ccbb"
        assertEquals(hex, ClearKey.toHex(hex.uppercase()))
        // StreamCorner's form: the 16 bytes as unpadded base64url.
        assertEquals(hex, ClearKey.toHex("dqbGXF6nYgRr10mi5jLMuw"))
        assertEquals(hex, ClearKey.toHex("dqbGXF6nYgRr10mi5jLMuw=="))
        assertNull(ClearKey.toHex("dqbGXF6nYgRr"), "not 16 bytes")
        assertNull(ClearKey.toHex("not a key!"))
    }

    private fun link(inline: String?) = runBlocking {
        newDrmExtractorLink("StreamCorner", "NBA", "https://lpnba.example/a.cs3.mpd", ExtractorLinkType.DASH, CLEARKEY_UUID) {
            key = "dqbGXF6nYgRr10mi5jLMuw"
            extractorData = inline
        }
    }

    @Test
    fun inlineManifestNeedsTheProviderToServeIt() {
        val hex = "76a6c65c5ea762046bd749a2e632ccbb"
        assertEquals(hex, ClearKey.hexKey(link(null)))
        assertNull(ClearKey.hexKey(link("<?xml version=\"1.0\"?><MPD/>")), "nothing serves the manifest")
        assertEquals(hex, ClearKey.hexKey(link("<?xml version=\"1.0\"?><MPD/>"), served = true))
        assertNull(ClearKey.hexKey(link("some other data"), served = true))
    }

    @Test
    fun mpdSegmentUrlsGoThroughTheProxy() {
        val mpd = """<SegmentList><Initialization sourceURL="https://cdn.example/i.mp4?a=1&amp;b=2"/><SegmentURL media="s-1.m4s"/>""" +
            """<SegmentURL media="$"/><SegmentTemplate media="$" /></SegmentList>""".replace("$\"", "\$Number\$\"")
        val out = StreamProxy().rewriteMpd(mpd, "https://cdn.example/dir/a.mpd", "tok")
        assertEquals(2, Regex("/proxy/tok/").findAll(out).count(), out)
        assertEquals(2, out.split("\$Number\$").size - 1, out)
    }

    @Test
    fun splitsAMultiPeriodManifestIntoOneManifestPerPeriod() {
        val mpd = """<?xml version="1.0"?><MPD mediaPresentationDuration="PT12S"><Period id="0" start="PT0S" duration="PT5S"><S t="0"/></Period>""" +
            """<Period id="1" start="PT5S" duration="PT7S"><S t="5"/></Period></MPD>"""
        val parts = StreamProxy.splitPeriods(mpd)!!
        assertEquals(2, parts.size)
        assertEquals("""<?xml version="1.0"?><MPD mediaPresentationDuration="PT7S"><Period id="1" duration="PT7S"><S t="5"/></Period></MPD>""", parts[1])
        assertEquals(1, parts[0].split("<Period ").size - 1)
        assertNull(StreamProxy.splitPeriods("<MPD/>"))
    }

    @Test
    fun segmentListsGetTheirCommonSegmentLength() {
        val list = """<SegmentList timescale="90000"><SegmentTimeline><S t="0" d="540000"/><S t="540000" d="540000"/><S t="1080000" d="300000"/></SegmentTimeline></SegmentList>"""
        assertEquals(true, StreamProxy.withSegmentDuration(list).startsWith("""<SegmentList timescale="90000" duration="540000">"""))
        val has = """<SegmentList duration="5"><S d="9"/></SegmentList>"""
        assertEquals(has, StreamProxy.withSegmentDuration(has))
    }
}
