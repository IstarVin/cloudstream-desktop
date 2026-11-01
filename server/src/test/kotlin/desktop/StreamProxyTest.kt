package desktop

import desktop.links.StreamProxy
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamProxyTest {
    private fun decode(line: String) =
        String(Base64.getUrlDecoder().decode(line.removePrefix("/proxy/tok/").substringBefore('/')))

    @Test
    fun rewritesSegmentsKeysAndVariants() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1
            #EXT-X-MEDIA:TYPE=AUDIO,URI="https://cdn.example/audio/en.m3u8"
            #EXTINF:4.0,
            seg-1.ts?sig=abc
            /abs/seg-2.ts
        """.trimIndent()
        val out = StreamProxy().rewrite(playlist, "https://host.example/path/index.m3u8?t=1", "tok").lines()

        assertEquals("#EXTM3U", out[0])
        val key = Regex("""URI="([^"]+)"""").find(out[1])!!.groupValues[1]
        assertEquals("https://host.example/path/key.bin", decode(key))
        val audio = Regex("""URI="([^"]+)"""").find(out[2])!!.groupValues[1]
        assertEquals("https://cdn.example/audio/en.m3u8", decode(audio))
        assertTrue(audio.endsWith("/en.m3u8"), "keeps the file name so mpv can sniff the type")
        assertEquals("https://host.example/path/seg-1.ts?sig=abc", decode(out[4]))
        assertEquals("https://host.example/abs/seg-2.ts", decode(out[5]))
    }
}
