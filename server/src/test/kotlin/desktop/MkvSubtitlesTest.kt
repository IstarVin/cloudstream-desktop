package desktop

import desktop.links.MkvSubtitles
import desktop.links.MkvTap
import desktop.links.StreamProxy
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MkvSubtitlesTest {
    // ---- a small Matroska writer ---------------------------------------------------------------------------------

    private fun id(v: Long): ByteArray {
        val n = when { v >= 0x1000000 -> 4; v >= 0x10000 -> 3; v >= 0x100 -> 2; else -> 1 }
        return ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
    }

    private fun size(v: Long): ByteArray {
        val bytes = ByteArray(8)
        for (i in 0 until 8) bytes[i] = (v shr (8 * (7 - i))).toByte()
        bytes[0] = 0x01
        return bytes
    }

    private fun el(id: Long, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return id(id) + size(body.size.toLong()) + body
    }

    private fun uint(id: Long, v: Long) = el(id, ByteArray(8) { (v shr (8 * (7 - it))).toByte() })
    private fun str(id: Long, v: String) = el(id, v.toByteArray())

    private fun block(track: Int, rel: Int, data: ByteArray) =
        byteArrayOf((0x80 or track).toByte(), (rel shr 8).toByte(), rel.toByte(), 0) + data

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater().apply { setInput(b); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    }

    private val assHeader = "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"

    /** Video track 1, zlib-compressed SRT track 2, ASS track 3; 20 clusters of 5 s, each with a big video block. */
    private fun sample(): ByteArray = build().first

    /** The file, and where each cluster starts in it. */
    private fun build(): Pair<ByteArray, List<Int>> {
        val random = Random(1)
        val tracks = el(0x1654AE6B,
            el(0xAE, uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MPEG4/ISO/AVC")),
            el(0xAE, uint(0xD7, 2), uint(0x83, 0x11), str(0x86, "S_TEXT/UTF8"), str(0x22B59C, "eng"),
                el(0x6D80, el(0x6240, uint(0x5032, 1), uint(0x5033, 0), el(0x5034, uint(0x4254, 0))))),
            el(0xAE, uint(0xD7, 3), uint(0x83, 0x11), str(0x86, "S_TEXT/ASS"), str(0x536E, "Signs"),
                el(0x63A2, assHeader.toByteArray())),
        )
        val clusters = (0 until 20).map { i ->
            el(0x1F43B675,
                uint(0xE7, i * 5000L),
                // Video bytes that happen to contain a cluster ID, to trip a naive scan.
                el(0xA3, block(1, 0, random.nextBytes(40_000) + byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75) + random.nextBytes(20_000))),
                el(0xA0, el(0xA1, block(2, 1000, deflate("Line $i".toByteArray()))), uint(0x9B, 2000)),
                el(0xA0, el(0xA1, block(3, 1500, "$i,0,Default,,0,0,0,,ASS $i".toByteArray())), uint(0x9B, 1000)),
            )
        }
        val head = el(0x1A45DFA3, str(0x4282, "matroska")) + id(0x18538067) +
            size((el(0x1549A966, uint(0x2AD7B1, 1_000_000)) + tracks).size.toLong() + clusters.sumOf { it.size }) +
            el(0x1549A966, uint(0x2AD7B1, 1_000_000)) + tracks
        val offsets = clusters.runningFold(head.size) { at, c -> at + c.size }.dropLast(1)
        return clusters.fold(head) { a, c -> a + c } to offsets
    }

    private fun feed(tap: MkvTap, bytes: ByteArray, from: Int, chunk: Int) {
        var p = from
        while (p < bytes.size) {
            val n = minOf(chunk, bytes.size - p)
            tap.feed(bytes, p, n)
            p += n
        }
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    fun readsTracksAndCuesFromTheStart() {
        val file = sample()
        val subs = MkvSubtitles()
        feed(MkvTap(0) { if (it) subs else null }, file, 0, 777)

        assertEquals(listOf(2L, 3L), subs.tracks.map { it.number })
        assertEquals("eng", subs.tracks[0].language)
        assertEquals("Signs", subs.tracks[1].name)
        assertTrue(subs.tracks[1].ass && subs.tracks[1].header!!.contains("[Events]"))

        val (cues, next) = subs.since(0)
        assertEquals(40, next)
        val srt = cues.filter { it.track == 2L }
        assertEquals((0 until 20).map { "Line $it" }, srt.map { it.text })
        assertEquals(10 * 5000L + 1000, srt[10].startMs)
        assertEquals(2000L, srt[10].durationMs)
        assertEquals("7,0,Default,,0,0,0,,ASS 7", cues.first { it.track == 3L && it.startMs == 7 * 5000L + 1500 }.text)
    }

    @Test
    fun resyncsFromTheMiddleAndIgnoresRepeats() {
        val (file, clusters) = build()
        val subs = MkvSubtitles()
        feed(MkvTap(0) { if (it) subs else null }, file, 0, 4096)
        val before = subs.since(0).second

        // A seek: a response from somewhere inside cluster 12; the whole file read again adds nothing.
        val seek = clusters[12] + 333
        val fresh = MkvSubtitles()
        val head = file.copyOf(clusters[0])
        MkvTap(0) { fresh }.feed(head, 0, head.size)
        assertEquals(subs.tracks.map { it.number }, fresh.tracks.map { it.number })
        feed(MkvTap(seek.toLong()) { fresh }, file, seek, 1500)
        val starts = fresh.since(0).first.filter { it.track == 2L }.map { it.startMs / 5000 }
        assertEquals((13 until 20).map { it.toLong() }, starts)

        feed(MkvTap(seek.toLong()) { subs }, file, seek, 1500)
        assertEquals(before, subs.since(0).second)
    }

    @Test
    fun leavesOtherFilesAlone() {
        var asked: Boolean? = null
        val tap = MkvTap(0) { asked = it; null }
        tap.feed("ftypisom and so on".toByteArray(), 0, 18)
        assertEquals(false, asked)
    }

    /** Through the proxy: the start of the file names the tracks, a seek adds the cues further on; an MP4 is let be. */
    @Test
    fun readsWhatTheProxyPassesOn() {
        val (file, clusters) = build()
        val mp4 = "\u0000\u0000\u0000\u0018ftypisom".toByteArray() + ByteArray(5000)
        LocalServer { ex ->
            val body = if (ex.requestURI.path.endsWith(".mp4")) mp4 else file
            val from = ex.requestHeaders.getFirst("Range")?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
            if (from == null) return@LocalServer ex.respond(200, body, "video/x-matroska")
            val start = from.groupValues[1].toInt()
            val end = from.groupValues[2].toIntOrNull() ?: (body.size - 1)
            ex.respond(206, body.copyOfRange(start, end + 1), "video/x-matroska", mapOf("Content-Range" to "bytes $start-$end/${body.size}"))
        }.use { upstream ->
            val proxy = StreamProxy()
            testApplication {
                routing { get("/proxy/{token}/{encoded}/{name?}") { proxy.serve(call, call.parameters["token"]!!, call.parameters["encoded"]!!) } }
                val path = proxy.register("${upstream.url}/v/episode.mkv", emptyMap(), null)
                assertEquals(null, proxy.embeddedSubtitles(path))
                // The browser reads the start, gives up on it, then seeks into cluster 15.
                client.get(path) { header(HttpHeaders.Range, "bytes=0-${clusters[2] - 1}") }.readRawBytes()
                val subs = proxy.embeddedSubtitles(path)!!
                assertEquals(listOf(2L, 3L), subs.tracks.map { it.number })
                assertEquals(listOf(0L, 5000L), subs.since(0).first.filter { it.track == 2L }.map { it.startMs - 1000 })
                val bytes = client.get(path) { header(HttpHeaders.Range, "bytes=${clusters[15] + 10}-") }.readRawBytes()
                assertContentEquals(file.copyOfRange(clusters[15] + 10, file.size), bytes)
                assertEquals((listOf(0, 1) + (16 until 20)).map { it * 5000L + 1000 }, subs.since(0).first.filter { it.track == 2L }.map { it.startMs })

                val other = proxy.register("${upstream.url}/v/movie.mp4", emptyMap(), null)
                client.get(other).readRawBytes()
                assertTrue(proxy.embeddedSubtitles(other) === StreamProxy.NOT_MATROSKA)
            }
        }
    }

    /** A real file from ffmpeg, when one is named in `MKV_SAMPLE`. */
    @Test
    fun readsARealFile() {
        val path = System.getenv("MKV_SAMPLE") ?: return
        val file = java.io.File(path).readBytes()
        val subs = MkvSubtitles()
        feed(MkvTap(0) { if (it) subs else null }, file, 0, 65536)
        println(subs.tracks.map { "${it.number} ${it.codec} ${it.language} ${it.name}" })
        val (cues, n) = subs.since(0)
        println("$n cues; " + cues.take(3) + " … " + cues.takeLast(2))
        val mid = MkvSubtitles()
        MkvTap(0) { mid }.feed(file, 0, 1 shl 20)
        val at = file.size / 2
        feed(MkvTap(at.toLong()) { mid }, file, at, 65536)
        println("from the middle: ${mid.since(0).second} cues, first ${mid.since(0).first.firstOrNull()}")
    }
}
