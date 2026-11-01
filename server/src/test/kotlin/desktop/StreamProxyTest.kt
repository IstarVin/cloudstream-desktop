package desktop

import desktop.links.StreamProxy
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun rewritesMasterPlaylistsAndInitSegments() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8

            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            https://other.example/720p/index.m3u8?sig=x
            #EXT-X-MAP:URI="init.mp4"
        """.trimIndent()
        val out = StreamProxy().rewrite(master, "https://host.example/v/master.m3u8", "tok").lines()
        assertEquals("https://host.example/v/360p/index.m3u8", decode(out[2]))
        assertEquals("", out[3], "blank lines stay blank")
        assertEquals("https://other.example/720p/index.m3u8?sig=x", decode(out[5]))
        assertEquals("https://host.example/v/init.mp4", decode(Regex("""URI="([^"]+)"""").find(out[6])!!.groupValues[1]))
    }

    /** The whole path: link headers reach the upstream, playlists come back rewritten, ranges pass through. */
    @Test
    fun servesThroughTheUpstreamWithTheLinksHeaders() {
        val seen = ConcurrentHashMap<String, String>()
        val segment = ByteArray(1000) { it.toByte() }
        LocalServer { ex ->
            seen[ex.requestURI.path] = "${ex.requestHeaders.getFirst("Referer")}|${ex.requestHeaders.getFirst("X-Token")}"
            when (ex.requestURI.path) {
                "/v/index.m3u8" -> ex.respond(200, "#EXTM3U\n#EXTINF:4.0,\nseg-1.ts\n", "application/vnd.apple.mpegurl")
                "/v/seg-1.ts" -> {
                    val range = ex.requestHeaders.getFirst("Range")
                    if (range == "bytes=100-199") ex.respond(206, segment.copyOfRange(100, 200), "video/mp2t", mapOf("Content-Range" to "bytes 100-199/1000"))
                    else ex.respond(200, segment, "video/mp2t")
                }
                else -> ex.respond(404, "")
            }
        }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val path = proxy.register("${upstream.url}/v/index.m3u8", mapOf("Referer" to "https://site.example/", "X-Token" to "abc"), null)
                val playlist = client.get(path).bodyAsText().lines()
                assertEquals("#EXTM3U", playlist[0])
                val segPath = playlist[2]
                assertTrue(segPath.startsWith("/proxy/"), segPath)

                assertContentEquals(segment, client.get(segPath).readRawBytes())
                val partial = client.get(segPath) { header(HttpHeaders.Range, "bytes=100-199") }
                assertEquals(206, partial.status.value)
                assertEquals("bytes 100-199/1000", partial.headers[HttpHeaders.ContentRange])
                assertContentEquals(segment.copyOfRange(100, 200), partial.readRawBytes())

                assertEquals(404, client.get("/proxy/unknown/${segPath.split('/')[3]}").status.value)
            }
            assertEquals("https://site.example/|abc", seen["/v/index.m3u8"])
            assertEquals("https://site.example/|abc", seen["/v/seg-1.ts"], "segments carry the link's headers too")
        }
    }
}
