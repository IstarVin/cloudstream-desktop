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
    @Test
    fun splitPeriodsKeepNonzeroSegmentTimelinesSeekable() {
        LocalServer { ex ->
            if (ex.requestURI.path.endsWith(".mpd")) {
                fun period(time: Int) = """<Period duration="PT8S"><AdaptationSet><Representation id="video"><SegmentList timescale="90000" presentationTimeOffset="$time"><Initialization sourceURL="init.m4s"/><SegmentTimeline><S t="$time" d="360000" r="1"/></SegmentTimeline><SegmentURL media="first.m4s"/><SegmentURL media="second.m4s"/></SegmentList></Representation></AdaptationSet></Period>"""
                ex.respond(200, "<?xml version=\"1.0\"?><MPD>${period(0)}${period(45938393)}</MPD>", "application/dash+xml")
            } else ex.respond(200, ex.requestURI.path, "video/mp4")
        }.use { upstream ->
            val proxy = StreamProxy()
            val periods = runBlocking { proxy.periodPaths(proxy.register("${upstream.url}/main.mpd", emptyMap(), null)) }!!
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val manifest = client.get(periods[1]).bodyAsText()
                assertTrue("<SegmentList" !in manifest && "<SegmentTemplate" in manifest)
                assertTrue("presentationTimeOffset=\"45938393\"" in manifest && "t=\"45938393\"" in manifest)
                val media = Regex("""\bmedia="([^"]+)"""").find(manifest)!!.groupValues[1]
                assertEquals("/first.m4s", client.get(media.replace("\$Number\$", "0")).bodyAsText())
                assertEquals("/second.m4s", client.get(media.replace("\$Number\$", "1")).bodyAsText())
            }
        }
    }

    @Test
    fun splitPeriodsUseCompactSegmentUrls() {
        val mediaPath = "/" + "long-cdn-path/".repeat(30)
        val seen = ConcurrentHashMap<String, String>()
        LocalServer { ex ->
            seen[ex.requestURI.path] = ex.requestHeaders.getFirst("Referer").orEmpty()
            if (ex.requestURI.path.endsWith(".mpd")) {
                val period = """<Period duration="PT4S"><AdaptationSet><Representation id="video"><SegmentList duration="4"><Initialization sourceURL="${mediaPath}init.m4s"/><SegmentURL media="${mediaPath}segment.m4s"/></SegmentList></Representation></AdaptationSet></Period>"""
                ex.respond(200, "<?xml version=\"1.0\"?><MPD>$period$period</MPD>", "application/dash+xml")
            } else ex.respond(200, "media", "video/mp4")
        }.use { upstream ->
            val proxy = StreamProxy()
            val path = proxy.register("${upstream.url}/main.mpd", mapOf("Referer" to "https://embed.example/"), null)
            val periods = runBlocking { proxy.periodPaths(path) }!!
            assertEquals(2, periods.size)
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                for (period in periods) {
                    val manifest = client.get(period).bodyAsText()
                    for (match in Regex("""(?:sourceURL|media)="([^"]+)"""").findAll(manifest)) {
                        val segment = match.groupValues[1]
                        assertTrue(segment.length < 128, "FFmpeg's SegmentList buffer must fit the segment: $segment")
                        assertEquals("media", client.get(segment).bodyAsText())
                    }
                }
            }
            assertEquals("https://embed.example/", seen["${mediaPath}segment.m4s"])
        }
    }

    @Test
    fun exposesSharedRepresentationProtectionToDashPlayers() {
        val cenc = """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" xmlns:cenc="urn:mpeg:cenc:2013" cenc:default_KID="3290efe4-c5cf-6716-5e84-6a0742c22cd9"/>"""
        val clearKey = """<ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e"/>"""
        fun manifest(second: String) = "<MPD><Period><AdaptationSet><Representation id=\"1\">$cenc</Representation>" +
            "<Representation id=\"2\">$second</Representation></AdaptationSet>" +
            "<AdaptationSet mimeType=\"application/mp4\"><Representation id=\"text\"/></AdaptationSet></Period></MPD>"
        val out = StreamProxy.withClearKeyProtection(manifest(cenc))
        assertTrue("<AdaptationSet>$cenc$clearKey<Representation" in out)
        assertTrue("<AdaptationSet mimeType=\"application/mp4\"><Representation" in out, "text stays unencrypted")
        assertEquals(out, StreamProxy.withClearKeyProtection(out))
        for (second in listOf("", cenc.replace("3290efe4", "00000000"))) {
            assertTrue("<AdaptationSet><Representation" in StreamProxy.withClearKeyProtection(manifest(second)), "don't lift mixed protection")
        }
        val alreadyNamed = manifest(cenc).replace(cenc, cenc + clearKey)
        assertTrue("<AdaptationSet>$cenc$clearKey<Representation" in StreamProxy.withClearKeyProtection(alreadyNamed))
    }

    private fun decode(line: String) =
        String(Base64.getUrlDecoder().decode(line.removePrefix("/proxy/tok/").substringBefore('/')))

    @Test
    fun servesDashTemplatesAndInitializationWithTheOriginalHeaders() {
        val requests = ConcurrentHashMap<String, String>()
        LocalServer { ex ->
            requests[ex.requestURI.toString()] = ex.requestHeaders.getFirst("Referer").orEmpty()
            if (ex.requestURI.path.endsWith(".mpd")) {
                val manifest = """<MPD><Period><AdaptationSet><Representation id="video">
                    <SegmentTemplate media="v/${'$'}RepresentationID${'$'}/${'$'}Number%05d${'$'}.m4s?sig=a&amp;b=c"
                        initialization="v/${'$'}RepresentationID${'$'}/init.mp4"/>
                    </Representation></AdaptationSet></Period></MPD>""".trimIndent()
                ex.respond(200, manifest, "application/dash+xml")
            } else ex.respond(200, "media", "video/mp4")
        }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val manifest = client.get(proxy.register("${upstream.url}/live/main.mpd", mapOf("Referer" to "https://embed.example/"), null)).bodyAsText()
                fun attribute(name: String) = Regex("""\b$name="([^"]+)"""").find(manifest)!!.groupValues[1].replace("&amp;", "&")
                val media = attribute("media").replace("\$RepresentationID\$", "video").replace("\$Number%05d\$", "00042")
                val init = attribute("initialization").replace("\$RepresentationID\$", "video")
                assertEquals("media", client.get(media).bodyAsText())
                assertEquals("media", client.get(init).bodyAsText())
                assertEquals("https://embed.example/", requests["/live/v/video/00042.m4s?sig=a&b=c"])
                assertEquals("https://embed.example/", requests["/live/v/video/init.mp4"])
                assertEquals(io.ktor.http.HttpStatusCode.BadRequest, client.get(attribute("media").substringBefore('?')).status)
            }
        }
    }

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

    /** GoPlay labels its DASH manifest mpegurl and lists only Widevine and PlayReady; dash.js needs ClearKey named. */
    @Test
    fun namesClearKeyInAManifestTheCdnCallsMpegurl() {
        val mpd = """<?xml version="1.0"?><MPD><Period><AdaptationSet>""" +
            """<ContentProtection value="cenc" schemeIdUri="urn:mpeg:dash:mp4protection:2011" cenc:default_KID="f32da79c-c8c7-4e4a-ab11-d85ed50e9368"/>""" +
            """<ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>""" +
            """<Representation><BaseURL>https://cdn.example/v.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        LocalServer { ex -> ex.respond(200, mpd, "application/x-mpegURL") }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val res = client.get(proxy.register("${upstream.url}/stream_dash.mpd?d=abc", emptyMap(), null))
                assertTrue(res.headers[HttpHeaders.ContentType]!!.startsWith("application/dash+xml"), res.headers[HttpHeaders.ContentType])
                val body = res.bodyAsText()
                assertEquals(1, Regex("e2719d58-a985-b3c9-781a-b030af78d30e").findAll(body).count(), body)
                assertTrue("urn:uuid:edef8ba9" in body, "other schemes stay")
                assertEquals(body, StreamProxy.withClearKeyProtection(body), "a manifest naming ClearKey is left alone")
            }
        }
    }
}
