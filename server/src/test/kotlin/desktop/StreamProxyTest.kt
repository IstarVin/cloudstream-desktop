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
import kotlinx.coroutines.runBlocking
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

    /** ffmpeg opens bare .vtt renditions as playlists and the video variants fail with them (HentaiHaven). */
    @Test
    fun dropsTextSubtitleRenditions() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:URI="snd/a.m3u8",TYPE=AUDIO,GROUP-ID="audio",NAME="English"
            #EXT-X-MEDIA:URI="s/en.vtt",TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="en",NAME="English"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="audio",SUBTITLES="subs"
            360p/v.m3u8
        """.trimIndent()
        assertTrue(StreamProxy.hasTextSubtitles(master))
        val out = StreamProxy().rewrite(master, "https://host.example/v/master.m3u8", "tok")
        assertTrue("TYPE=SUBTITLES" !in out && "SUBTITLES=" !in out && "en.vtt" !in out)
        assertTrue("TYPE=AUDIO" in out)
        assertTrue(!StreamProxy.hasTextSubtitles(master.replace("en.vtt", "en.m3u8")))
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

    /** ffmpeg won't open a `data:` key itself (GoPlay's SAMPLE-AES keys), so the proxy answers it. */
    @Test
    fun servesInlineKeys() {
        val data = "data:application/octet-stream;base64,NqW2zqmvjXE0rSEbcmEEFg=="
        LocalServer { ex ->
            ex.respond(200, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"$data\",KEYFORMAT=\"identity\"\n#EXTINF:4.0,\nseg-1.mp4\n#EXT-X-ENDLIST\n", "application/x-mpegURL")
        }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val playlist = client.get(proxy.register("${upstream.url}/v/index.m3u8", emptyMap(), null)).bodyAsText().lines()
                val key = Regex("""URI="([^"]+)"""").find(playlist[1])!!.groupValues[1]
                assertTrue(key.startsWith("/proxy/"), key)
                assertContentEquals(Base64.getDecoder().decode("NqW2zqmvjXE0rSEbcmEEFg=="), client.get(key).readRawBytes())
                assertTrue(playlist[1].endsWith(",KEYFORMAT=\"identity\""), playlist[1])
            }
        }
    }

    /** HentaiHaven's CDN labels its fMP4 init segment mpegurl; decoding it as text corrupts the bytes. */
    @Test
    fun keepsBinaryBodiesLabelledAsPlaylists() {
        val init = ByteArray(64) { (it * 7 + 0x80).toByte() }
        LocalServer { ex -> ex.respond(200, init, "application/x-mpegURL") }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                assertContentEquals(init, client.get(proxy.register("${upstream.url}/snd/i.mp4", emptyMap(), null)).readRawBytes())
            }
        }
    }

    /** GoPlay's playlist URLs work once, and mpv asks for a master twice; live playlists must still reload. */
    @Test
    fun fetchesStaticPlaylistsFromTheUpstreamOnce() {
        val hits = ConcurrentHashMap<String, Int>()
        LocalServer { ex ->
            val path = ex.requestURI.path
            if (hits.merge(path, 1, Int::plus)!! > 1 && path != "/live.m3u8") return@LocalServer ex.respond(403, "")
            when (path) {
                "/master.m3u8" -> ex.respond(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvod.m3u8\n", "application/x-mpegURL")
                "/vod.m3u8" -> ex.respond(200, "#EXTM3U\n#EXTINF:4.0,\nseg.ts\n#EXT-X-ENDLIST\n", "application/x-mpegURL")
                "/live.m3u8" -> ex.respond(200, "#EXTM3U\n#EXTINF:4.0,\nseg${hits[path]}.ts\n", "application/x-mpegURL")
                else -> ex.respond(404, "")
            }
        }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val master = proxy.register("${upstream.url}/master.m3u8", emptyMap(), null)
                val first = client.get(master).bodyAsText()
                assertEquals(first, client.get(master).bodyAsText())
                val vod = first.lines()[2]
                assertEquals(client.get(vod).bodyAsText(), client.get(vod).bodyAsText())

                val live = proxy.register("${upstream.url}/live.m3u8", emptyMap(), null)
                assertTrue(client.get(live).bodyAsText() != client.get(live).bodyAsText(), "live playlists reach the upstream every time")
            }
            assertEquals(1, hits["/master.m3u8"])
            assertEquals(1, hits["/vod.m3u8"])
        }
    }

    /** GoPlay: SAMPLE-AES fMP4 with an inline key, behind playlists that work once. mpv needs the key up front. */
    @Test
    fun readsASampleAesKeyAheadWithoutSpendingThePlaylists() {
        val hits = ConcurrentHashMap<String, Int>()
        val media = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"data:application/octet-stream;base64,NqW2zqmvjXE0rSEbcmEEFg==\",KEYFORMAT=\"identity\"\n" +
            "#EXTINF:4.0,\nseg.mp4\n#EXT-X-ENDLIST\n"
        LocalServer { ex ->
            if (hits.merge(ex.requestURI.path, 1, Int::plus)!! > 1) return@LocalServer ex.respond(403, "")
            when (ex.requestURI.path) {
                "/master.m3u8" -> ex.respond(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo.m3u8\n", "application/x-mpegURL")
                "/video.m3u8" -> ex.respond(200, media, "application/x-mpegURL")
                "/plain.m3u8" -> ex.respond(200, "#EXTM3U\n#EXTINF:4.0,\nseg.ts\n#EXT-X-ENDLIST\n", "application/x-mpegURL")
                else -> ex.respond(404, "")
            }
        }.use { upstream ->
            val proxy = StreamProxy()
            val master = proxy.register("${upstream.url}/master.m3u8", emptyMap(), null)
            assertEquals("36a5b6cea9af8d7134ad211b72610416", runBlocking { proxy.sampleAesKey(master) })
            assertEquals(null, runBlocking { proxy.sampleAesKey(proxy.register("${upstream.url}/plain.m3u8", emptyMap(), null)) })
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val variant = client.get(master).bodyAsText().lines()[2]
                assertTrue(client.get(variant).bodyAsText().contains("#EXT-X-KEY"), "mpv still gets the playlists")
            }
        }
    }

    /** ffmpeg cuts URLs at 4096 bytes; GoPlay's playlist URLs are about 4.5 KB before encoding. */
    @Test
    fun keepsLongUrlsUnderFfmpegsLimit() {
        val seen = ConcurrentHashMap<String, String>()
        LocalServer { ex ->
            seen[ex.requestURI.path] = ex.requestURI.rawQuery
            when (ex.requestURI.path) {
                "/stream.m3u8" -> ex.respond(200, "#EXTM3U\n#EXTINF:4.0,\nseg.ts?d=${"y".repeat(4500)}\n#EXT-X-ENDLIST\n", "application/x-mpegURL")
                else -> ex.respond(200, "segment", "video/mp2t")
            }
        }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val master = proxy.register("${upstream.url}/stream.m3u8?d=${"x".repeat(4500)}", emptyMap(), null)
                assertTrue(master.length < 200 && master.endsWith("/stream.m3u8"), master)
                val segment = client.get(master).bodyAsText().lines()[2]
                assertTrue(segment.length < 200, segment)
                assertEquals("segment", client.get(segment).bodyAsText())
                assertEquals(404, client.get(master.replace("/~", "/~x")).status.value)
            }
            assertEquals("d=${"x".repeat(4500)}", seen["/stream.m3u8"])
            assertEquals("d=${"y".repeat(4500)}", seen["/seg.ts"])
        }
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
