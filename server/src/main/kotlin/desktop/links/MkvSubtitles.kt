package desktop.links

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * The text subtitle tracks of one Matroska stream and the cues read from it so far. A browser can't show subtitles
 * muxed into an MKV, so [MkvTap]s pick them out of the bytes the proxy passes to it: only what the browser downloads
 * is read, nothing is fetched twice, and cues arrive as the video buffers.
 */
class MkvSubtitles {
    class Track internal constructor(
        val number: Long,
        val codec: String,
        val language: String?,
        val name: String?,
        /** ASS/SSA script header (styles and the `[Events]` format), from CodecPrivate. */
        val header: String?,
        val default: Boolean,
        val forced: Boolean,
        val defaultDurationMs: Long?,
        internal val encoding: Encoding?,
    ) {
        val ass get() = codec == "S_TEXT/ASS" || codec == "S_TEXT/SSA"
    }

    /** [text] is the block as stored: plain text, WebVTT cue text, or an ASS event line without its times. */
    data class Cue(val track: Long, val startMs: Long, val durationMs: Long, val text: String)

    /** Compression applied to a track's frames: zlib, or a stripped header to put back. */
    internal class Encoding(val zlib: Boolean, val prefix: ByteArray?, val frames: Boolean, val private: Boolean) {
        fun decode(bytes: ByteArray): ByteArray = when {
            zlib -> inflate(bytes)
            prefix != null -> prefix + bytes
            else -> bytes
        }
    }

    @Volatile var timecodeScale = 1_000_000L
        internal set
    @Volatile var tracks: List<Track> = emptyList()
        private set

    private val cues = ArrayList<Cue>()
    private val seen = HashSet<Cue>()
    private val _version = MutableStateFlow(0)
    /** Goes up whenever a track list or a new cue arrives. */
    val version: StateFlow<Int> = _version

    internal fun setTracks(list: List<Track>) {
        // The first Tracks element read is the file's; a stray copy elsewhere doesn't replace it.
        if (tracks.isNotEmpty() || list.isEmpty()) return
        tracks = list
        _version.value++
    }

    internal fun add(cue: Cue) = synchronized(this) {
        // A seek back reads the same clusters again.
        if (seen.add(cue)) {
            cues.add(cue)
            _version.value++
        }
    }

    /** Cues from index [from] on, and the index to ask from next time. */
    fun since(from: Int): Pair<List<Cue>, Int> = synchronized(this) {
        val start = from.coerceIn(0, cues.size)
        cues.subList(start, cues.size).toList() to cues.size
    }

    companion object {
        val TEXT_CODECS = setOf("S_TEXT/UTF8", "S_TEXT/ASCII", "S_TEXT/WEBVTT", "S_TEXT/ASS", "S_TEXT/SSA")

        internal fun inflate(bytes: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream(bytes.size * 3)
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}

/**
 * Reads one HTTP response of a Matroska file as it streams through the proxy, starting at byte [start] of the file,
 * and adds what it finds to the [MkvSubtitles] that [session] returns. From the start of the file it reads the
 * segment info and tracks; a response that starts elsewhere (a seek) is scanned for the next cluster first. Video and
 * audio blocks are skipped over without being kept, so the tap holds only a few kilobytes at a time.
 *
 * [session] is asked for once the file is known to be Matroska (always for a response from the middle, since only a
 * file already known as one gets a tap there); it returns null to stop. Nothing here ever throws into the stream.
 */
class MkvTap(start: Long, private val session: (isMatroska: Boolean) -> MkvSubtitles?) {
    private var subs: MkvSubtitles? = null
    private var buf = ByteArray(16 * 1024)
    private var len = 0
    /** Payload bytes still to pass over before the next element. */
    private var skip = 0L
    private var syncing = start != 0L
    private var checkedMagic = start != 0L
    private var clusterTc: Long? = null
    private var stopped = false

    fun feed(data: ByteArray, off: Int, n: Int) {
        if (stopped) return
        try {
            var o = off
            var r = n
            if (skip > 0) {
                val k = minOf(skip, r.toLong()).toInt()
                skip -= k
                o += k
                r -= k
            }
            if (r == 0) return
            append(data, o, r)
            if (!checkedMagic) {
                if (len < 4) return
                checkedMagic = true
                subs = session(Ebml.id(buf, 0, len)?.first == Ebml.EBML)
                if (subs == null) return stop()
            } else if (subs == null) {
                subs = session(true) ?: return stop()
            }
            process()
        } catch (_: Exception) {
            stop()
        }
    }

    private fun stop() {
        stopped = true
        buf = ByteArray(0)
        len = 0
    }

    private fun append(data: ByteArray, off: Int, n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(len + n, buf.size * 2))
        System.arraycopy(data, off, buf, len, n)
        len += n
    }

    private fun process() {
        val subs = subs ?: return
        var p = 0
        while (p < len) {
            if (syncing) {
                val found = findCluster(p)
                if (found < -1) {
                    p = -2 - found // a cluster may start here; wait for the bytes that tell
                    break
                }
                if (found < 0) {
                    // Keep a few bytes in case a cluster's ID straddles the next chunk.
                    p = maxOf(p, len - SYNC_TAIL)
                    break
                }
                p = found
                syncing = false
            }
            val (id, idLen) = Ebml.id(buf, p, len) ?: break
            if (id == Ebml.INVALID) { syncing = true; p++; continue }
            val (size, sizeLen) = Ebml.size(buf, p + idLen, len) ?: break
            if (size == Long.MAX_VALUE) { syncing = true; p++; continue }
            val data = p + idLen + sizeLen
            val fits = size >= 0 && data + size <= len
            when (id) {
                Ebml.SEGMENT, Ebml.CLUSTER -> {
                    if (id == Ebml.CLUSTER) clusterTc = null
                    p = data
                }
                Ebml.TIMECODE -> {
                    if (size !in 1..8) { syncing = true; p++; continue }
                    if (!fits) break
                    clusterTc = Ebml.uint(buf, data, size.toInt())
                    p = data + size.toInt()
                }
                Ebml.INFO, Ebml.TRACKS -> {
                    if (size < 0 || size > MAX_HEADER) { syncing = true; p++; continue }
                    if (!fits) { reserve(data + size.toInt() - p); break }
                    if (id == Ebml.INFO) readInfo(subs, data, size.toInt()) else subs.setTracks(readTracks(data, size.toInt()))
                    p = data + size.toInt()
                }
                Ebml.SIMPLE_BLOCK, Ebml.BLOCK_GROUP -> {
                    if (size < 0) { syncing = true; p++; continue }
                    val wanted = when {
                        size > MAX_BLOCK -> false
                        id == Ebml.BLOCK_GROUP -> true
                        else -> isTextTrack(subs, data) ?: break // the track number isn't in yet
                    }
                    if (wanted) {
                        if (!fits) { reserve(data + size.toInt() - p); break }
                        if (id == Ebml.SIMPLE_BLOCK) readBlock(subs, data, size.toInt(), null)
                        else readGroup(subs, data, size.toInt())
                        p = data + size.toInt()
                    } else p = pass(data, size)
                }
                else -> {
                    if (size < 0) { syncing = true; p++; continue }
                    p = pass(data, size)
                }
            }
        }
        if (p > 0) {
            System.arraycopy(buf, p, buf, 0, len - p)
            len -= p
        }
    }

    /** Whether a SimpleBlock at [data] belongs to a text track; null until its track number is buffered. */
    private fun isTextTrack(subs: MkvSubtitles, data: Int): Boolean? {
        val (track, _) = Ebml.vint(buf, data, len) ?: return null
        return subs.tracks.any { it.number == track }
    }

    /** Passes over a payload, the part not yet buffered included; returns where parsing goes on. */
    private fun pass(data: Int, size: Long): Int {
        if (data + size <= len) return data + size.toInt()
        skip = data + size - len
        return len
    }

    private fun reserve(n: Int) {
        if (n > buf.size) buf = buf.copyOf(n)
    }

    /**
     * The next plausible cluster at or after [from]: its ID, a size, then a Timecode (after an optional CRC). -1 when
     * there's none, `-2 - i` when one may start at `i` but the bytes to tell aren't in yet.
     */
    private fun findCluster(from: Int): Int {
        var i = from
        while (i + 4 <= len) {
            if (buf[i] == 0x1F.toByte() && buf[i + 1] == 0x43.toByte() && buf[i + 2] == 0xB6.toByte() && buf[i + 3] == 0x75.toByte()) {
                val ok = plausibleCluster(i)
                if (ok == null) return -2 - i
                if (ok) return i
            }
            i++
        }
        return -1
    }

    private fun plausibleCluster(at: Int): Boolean? {
        val (_, sizeLen) = Ebml.size(buf, at + 4, len) ?: return null
        var p = at + 4 + sizeLen
        var (child, childLen) = Ebml.id(buf, p, len) ?: return null
        if (child == Ebml.CRC32) {
            val (crcSize, crcSizeLen) = Ebml.size(buf, p + childLen, len) ?: return null
            if (crcSize != 4L) return false
            p += childLen + crcSizeLen + 4
            Ebml.id(buf, p, len)?.let { child = it.first; childLen = it.second } ?: return null
        }
        return child == Ebml.TIMECODE
    }

    private fun readInfo(subs: MkvSubtitles, start: Int, size: Int) {
        Ebml.children(buf, start, start + size) { id, d, n ->
            if (id == Ebml.TIMECODE_SCALE && n in 1..8) subs.timecodeScale = Ebml.uint(buf, d, n).takeIf { it > 0 } ?: 1_000_000L
        }
    }

    private fun readTracks(start: Int, size: Int): List<MkvSubtitles.Track> {
        val out = mutableListOf<MkvSubtitles.Track>()
        Ebml.children(buf, start, start + size) { id, d, n -> if (id == Ebml.TRACK_ENTRY) readTrack(d, n)?.let(out::add) }
        return out
    }

    private fun readTrack(start: Int, size: Int): MkvSubtitles.Track? {
        var number = 0L
        var type = 0L
        var codec = ""
        var lang: String? = null
        var bcp47: String? = null
        var name: String? = null
        var private: ByteArray? = null
        var default = true
        var forced = false
        var defaultDuration: Long? = null
        var encoding: MkvSubtitles.Encoding? = null
        var encrypted = false
        Ebml.children(buf, start, start + size) { id, d, n ->
            when (id) {
                Ebml.TRACK_NUMBER -> number = Ebml.uint(buf, d, n)
                Ebml.TRACK_TYPE -> type = Ebml.uint(buf, d, n)
                Ebml.CODEC_ID -> codec = Ebml.string(buf, d, n)
                Ebml.LANGUAGE -> lang = Ebml.string(buf, d, n)
                Ebml.LANGUAGE_BCP47 -> bcp47 = Ebml.string(buf, d, n)
                Ebml.NAME -> name = Ebml.string(buf, d, n)
                Ebml.CODEC_PRIVATE -> private = buf.copyOfRange(d, d + n)
                Ebml.FLAG_DEFAULT -> default = Ebml.uint(buf, d, n) != 0L
                Ebml.FLAG_FORCED -> forced = Ebml.uint(buf, d, n) != 0L
                Ebml.DEFAULT_DURATION -> defaultDuration = Ebml.uint(buf, d, n) / 1_000_000
                Ebml.CONTENT_ENCODINGS -> Ebml.children(buf, d, d + n) { eid, ed, en ->
                    if (eid == Ebml.CONTENT_ENCODING) readEncoding(ed, en).let { (enc, crypt) ->
                        if (crypt) encrypted = true else if (enc != null) encoding = enc
                    }
                }
            }
        }
        if (type != SUBTITLE_TYPE || codec !in MkvSubtitles.TEXT_CODECS || encrypted || number == 0L) return null
        val header = private?.let { p -> encoding?.takeIf { it.private }?.decode(p) ?: p }
            ?.let { String(it, Charsets.UTF_8).trimEnd('\u0000') }
        return MkvSubtitles.Track(
            number, codec, bcp47 ?: lang?.takeIf { it != "und" }, name?.takeIf { it.isNotBlank() },
            header?.takeIf { codec == "S_TEXT/ASS" || codec == "S_TEXT/SSA" }, default, forced, defaultDuration,
            encoding?.takeIf { it.frames },
        )
    }

    /** One ContentEncoding: the compression it applies (if understood), and whether it's encryption instead. */
    private fun readEncoding(start: Int, size: Int): Pair<MkvSubtitles.Encoding?, Boolean> {
        var scope = 1L
        var type = 0L
        var algo = 0L
        var settings: ByteArray? = null
        Ebml.children(buf, start, start + size) { id, d, n ->
            when (id) {
                Ebml.CONTENT_ENCODING_SCOPE -> scope = Ebml.uint(buf, d, n)
                Ebml.CONTENT_ENCODING_TYPE -> type = Ebml.uint(buf, d, n)
                Ebml.CONTENT_COMPRESSION -> Ebml.children(buf, d, d + n) { cid, cd, cn ->
                    when (cid) {
                        Ebml.CONTENT_COMP_ALGO -> algo = Ebml.uint(buf, cd, cn)
                        Ebml.CONTENT_COMP_SETTINGS -> settings = buf.copyOfRange(cd, cd + cn)
                    }
                }
            }
        }
        if (type != 0L) return null to true
        val frames = scope and 1L != 0L
        val private = scope and 2L != 0L
        val enc = when (algo) {
            0L -> MkvSubtitles.Encoding(zlib = true, prefix = null, frames = frames, private = private)
            3L -> MkvSubtitles.Encoding(zlib = false, prefix = settings, frames = frames, private = private)
            else -> return null to true // bzlib/lzo: can't read those frames
        }
        return enc to false
    }

    private fun readGroup(subs: MkvSubtitles, start: Int, size: Int) {
        var block = -1
        var blockSize = 0
        var duration: Long? = null
        Ebml.children(buf, start, start + size) { id, d, n ->
            when (id) {
                Ebml.BLOCK -> { block = d; blockSize = n }
                Ebml.BLOCK_DURATION -> duration = Ebml.uint(buf, d, n)
            }
        }
        if (block >= 0) readBlock(subs, block, blockSize, duration)
    }

    private fun readBlock(subs: MkvSubtitles, start: Int, size: Int, duration: Long?) {
        val cluster = clusterTc ?: return
        val end = start + size
        val (number, numLen) = Ebml.vint(buf, start, end) ?: return
        val track = subs.tracks.firstOrNull { it.number == number } ?: return
        val p = start + numLen
        if (p + 3 > end) return
        val rel = ((buf[p].toInt() shl 8) or (buf[p + 1].toInt() and 0xFF)).toShort().toLong()
        val flags = buf[p + 2].toInt()
        if (flags and 0x06 != 0) return // laced: never used for subtitles
        var bytes = buf.copyOfRange(p + 3, end)
        track.encoding?.let { bytes = it.decode(bytes) }
        val text = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
        if (text.isBlank()) return
        val scale = subs.timecodeScale
        val startMs = (cluster + rel) * scale / 1_000_000
        val durationMs = duration?.let { it * scale / 1_000_000 } ?: track.defaultDurationMs ?: DEFAULT_DURATION_MS
        subs.add(MkvSubtitles.Cue(track.number, startMs, durationMs, text))
    }

    private companion object {
        const val SUBTITLE_TYPE = 0x11L
        const val SYNC_TAIL = 32
        const val MAX_HEADER = 8 * 1024 * 1024
        /** Subtitle blocks are small; a bigger one is video or audio and is passed over. */
        const val MAX_BLOCK = 256 * 1024L
        const val DEFAULT_DURATION_MS = 4000L
    }
}

/** Reading EBML, the binary format of Matroska: elements are an ID, a size and a payload, all variable-length. */
internal object Ebml {
    const val INVALID = -1L
    const val EBML = 0x1A45DFA3L
    const val SEGMENT = 0x18538067L
    const val INFO = 0x1549A966L
    const val TIMECODE_SCALE = 0x2AD7B1L
    const val TRACKS = 0x1654AE6BL
    const val TRACK_ENTRY = 0xAEL
    const val TRACK_NUMBER = 0xD7L
    const val TRACK_TYPE = 0x83L
    const val CODEC_ID = 0x86L
    const val CODEC_PRIVATE = 0x63A2L
    const val LANGUAGE = 0x22B59CL
    const val LANGUAGE_BCP47 = 0x22B59DL
    const val NAME = 0x536EL
    const val FLAG_DEFAULT = 0x88L
    const val FLAG_FORCED = 0x55AAL
    const val DEFAULT_DURATION = 0x23E383L
    const val CONTENT_ENCODINGS = 0x6D80L
    const val CONTENT_ENCODING = 0x6240L
    const val CONTENT_ENCODING_SCOPE = 0x5032L
    const val CONTENT_ENCODING_TYPE = 0x5033L
    const val CONTENT_COMPRESSION = 0x5034L
    const val CONTENT_COMP_ALGO = 0x4254L
    const val CONTENT_COMP_SETTINGS = 0x4255L
    const val CLUSTER = 0x1F43B675L
    const val TIMECODE = 0xE7L
    const val CRC32 = 0xBFL
    const val SIMPLE_BLOCK = 0xA3L
    const val BLOCK_GROUP = 0xA0L
    const val BLOCK = 0xA1L
    const val BLOCK_DURATION = 0x9BL

    /** An element ID (marker bits kept) and its length, [INVALID] for a byte no ID starts with; null if cut off. */
    fun id(b: ByteArray, at: Int, end: Int): Pair<Long, Int>? {
        if (at >= end) return null
        val first = b[at].toInt() and 0xFF
        val n = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> return INVALID to 1
        }
        if (at + n > end) return null
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v to n
    }

    /** A variable-length integer with its marker removed, and its length; null if cut off or malformed. */
    fun vint(b: ByteArray, at: Int, end: Int): Pair<Long, Int>? {
        if (at >= end) return null
        val first = b[at].toInt() and 0xFF
        if (first == 0) return null
        val n = Integer.numberOfLeadingZeros(first) - 23
        if (at + n > end) return null
        var v = (first and (0xFF shr n)).toLong()
        for (i in 1 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v to n
    }

    /** An element size and its length; the size is -1 for "unknown" (all value bits set). Null if cut off. */
    fun size(b: ByteArray, at: Int, end: Int): Pair<Long, Int>? {
        if (at >= end) return null
        if (b[at].toInt() == 0) return Long.MAX_VALUE to 1 // malformed: makes the caller resync
        val (v, n) = vint(b, at, end) ?: return null
        return (if (v == (1L shl (7 * n)) - 1) -1L else v) to n
    }

    fun uint(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    fun string(b: ByteArray, at: Int, n: Int) = String(b, at, n, Charsets.UTF_8).trimEnd('\u0000')

    /** Calls [each] with every child element (ID, payload start, payload length) of a payload held whole in [b]. */
    inline fun children(b: ByteArray, start: Int, end: Int, each: (id: Long, data: Int, size: Int) -> Unit) {
        var p = start
        while (p < end) {
            val (id, idLen) = id(b, p, end) ?: return
            if (id == INVALID) return
            val (size, sizeLen) = size(b, p + idLen, end) ?: return
            val data = p + idLen + sizeLen
            if (size < 0 || data + size > end) return
            each(id, data, size.toInt())
            p = data + size.toInt()
        }
    }
}
