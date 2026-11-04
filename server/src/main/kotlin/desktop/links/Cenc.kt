package desktop.links

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** [bytes] of a file starting at [start], and the file's length. */
class Fetched(val start: Long, val bytes: ByteArray, val total: Long)

/** An MP4 this can't decrypt: another encryption scheme, or sample data it can't locate. */
class CencUnsupported(message: String) : RuntimeException(message)

/**
 * A fragmented MP4 with `cenc` (AES-CTR) or `cbcs` (pattern AES-CBC) protection, served in the clear at the same length.
 *
 * Players take a ClearKey through the device's DRM, and some devices can't decrypt with it (the Android emulator's
 * ClearKey service is denied the app's decrypt buffer). The server has the key, so it decrypts instead. Every edit
 * keeps the file's size, so the byte ranges of a manifest's `SegmentBase` and a `sidx` still hold: the encrypted sample
 * entries get their original names back (`encv` becomes `avc1`), and the protection boxes become `free`.
 *
 * [apply] works on whatever part of the file the caller holds, which is what a ranged request needs.
 */
class CencFile private constructor(
    private val key: ByteArray,
    private val patches: List<Patch>,
    private val tracks: Map<Int, TrackCrypto>,
    private val fragments: List<Fragment>,
    private val fetch: (Long, Long) -> Fetched,
) {
    private class Patch(val offset: Long, val bytes: ByteArray)
    private class TrackCrypto(val ivSize: Int, val constantIv: ByteArray?, val cbcs: Boolean, val cryptBlocks: Int, val skipBlocks: Int, val description: Int)
    private class Fragment(val offset: Long, val size: Long)

    /** [subs] holds a (clear, encrypted) byte count pair per subsample, or is null when the whole sample is encrypted. */
    private class Sample(val offset: Long, val size: Int, val iv: ByteArray, val subs: IntArray?, val crypto: TrackCrypto)

    /** [renames] are the offsets of the box names (in the fragment's own header) to turn into `free`. */
    private class Plan(val samples: List<Sample>, val renames: List<Long>)

    private val plans = object : LinkedHashMap<Int, Plan>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Plan>) = size > PLANS_KEPT
    }

    /** Decrypts and un-marks [len] bytes of [buf] from [off]; they are the file's bytes from [pos]. */
    fun apply(buf: ByteArray, off: Int, len: Int, pos: Long) {
        val end = pos + len
        for (p in patches) {
            val from = maxOf(p.offset, pos)
            val to = minOf(p.offset + p.bytes.size, end)
            if (from < to) System.arraycopy(p.bytes, (from - p.offset).toInt(), buf, off + (from - pos).toInt(), (to - from).toInt())
        }
        var i = firstFragmentEnding(pos)
        while (i < fragments.size && fragments[i].offset < end) {
            val plan = plan(i)
            for (name in plan.renames) {
                for (k in 0 until 4) {
                    val at = name + k
                    if (at in pos until end) buf[off + (at - pos).toInt()] = FREE[k]
                }
            }
            val samples = plan.samples
            var s = firstSampleEnding(samples, pos)
            while (s < samples.size && samples[s].offset < end) decrypt(samples[s++], buf, off, pos, end)
            i++
        }
    }

    private fun firstFragmentEnding(pos: Long): Int {
        var lo = 0
        var hi = fragments.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (fragments[mid].offset + fragments[mid].size > pos) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun firstSampleEnding(samples: List<Sample>, pos: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].offset + samples[mid].size > pos) hi = mid else lo = mid + 1
        }
        return lo
    }

    @Synchronized
    private fun plan(i: Int): Plan = plans.getOrPut(i) { loadPlan(fragments[i]) }

    /** Decrypts the part of [sample] inside the bytes [pos, end) held in [buf] from [off]. */
    private fun decrypt(sample: Sample, buf: ByteArray, off: Int, pos: Long, end: Long) {
        val from = maxOf(sample.offset, pos)
        val to = minOf(sample.offset + sample.size, end)
        var at = sample.offset // where the current part starts
        var encrypted = 0L // encrypted bytes before it; the keystream runs on across the sample's subsamples
        val subs = sample.subs
        var part = 0
        while (at < sample.offset + sample.size) {
            val clear = if (subs == null) 0L else if (part < subs.size) subs[part].toLong() and 0xffffffffL else sample.size.toLong()
            val enc = if (subs == null) sample.size.toLong() else if (part + 1 < subs.size) subs[part + 1].toLong() and 0xffffffffL else sample.size.toLong()
            val encStart = at + clear
            val encEnd = minOf(encStart + enc, sample.offset + sample.size)
            val x = maxOf(encStart, from)
            val y = minOf(encEnd, to)
            if (x < y) {
                if (sample.crypto.cbcs) {
                    decryptCbcs(sample, encStart, encEnd, buf, off, pos, x, y)
                } else {
                    val cipher = counterAt(sample.iv, encrypted + (x - encStart))
                    val start = off + (x - pos).toInt()
                    cipher.update(buf, start, (y - x).toInt(), buf, start)
                }
            }
            encrypted += encEnd - encStart
            at = encEnd
            part += 2
            if (subs == null) break
        }
    }

    /** CBCS restarts its IV and block pattern at each subsample; skipped blocks and partial blocks stay clear. */
    private fun decryptCbcs(sample: Sample, start: Long, end: Long, buf: ByteArray, off: Int, pos: Long, from: Long, to: Long) {
        val length = ((end - start) / 16 * 16).toInt()
        if (length == 0) return
        val bytes = if (start >= pos && start + length <= pos + buf.size - off) {
            buf.copyOfRange(off + (start - pos).toInt(), off + (start - pos).toInt() + length)
        } else fetch(start, start + length - 1).bytes
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(sample.iv))
        val crypt = sample.crypto.cryptBlocks
        val skip = sample.crypto.skipBlocks
        var at = 0
        while (at < length) {
            val count = if (crypt == 0 && skip == 0) length - at else minOf(crypt * 16, length - at)
            if (count == 0) throw CencUnsupported("Invalid CBCS encryption pattern")
            cipher.update(bytes, at, count, bytes, at)
            at += count + skip * 16
        }
        val x = maxOf(from, start)
        val y = minOf(to, start + length)
        if (x < y) bytes.copyInto(buf, off + (x - pos).toInt(), (x - start).toInt(), (y - start).toInt())
    }

    private fun counterAt(iv: ByteArray, encryptedBefore: Long): Cipher {
        val counter = iv.copyOf()
        var carry = encryptedBefore / 16
        for (j in 15 downTo 0) {
            if (carry == 0L) break
            val sum = (counter[j].toInt() and 0xff) + (carry and 0xff).toInt()
            counter[j] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val skip = (encryptedBefore % 16).toInt()
        if (skip > 0) cipher.update(ByteArray(skip))
        return cipher
    }

    /**
     * The samples of [fragment] with where their bytes are and how they're encrypted. The boxes that carry the
     * encryption (`senc`, `saiz`, `saio`) are renamed too: Chromium's MP4 parser refuses a fragment that has them for
     * a track whose sample entry says it isn't encrypted.
     */
    private fun loadPlan(fragment: Fragment): Plan {
        val head = fetch(fragment.offset, fragment.offset + minOf(fragment.size, MOOF_GUESS) - 1).bytes
        if (head.size < 8 || type(head, 4) != "moof") throw CencUnsupported("A fragment doesn't start with a moof")
        val moofSize = u32(head, 0).toInt()
        val moof = if (moofSize <= head.size) head else fetch(fragment.offset, fragment.offset + moofSize - 1).bytes
        if (moof.size < moofSize) throw CencUnsupported("The media host cut a moof short")

        val samples = ArrayList<Sample>()
        val renames = ArrayList<Long>()
        var nextBase = fragment.offset
        for (traf in boxes(moof, 8, moofSize).filter { it.type == "traf" }) {
            val kids = boxes(moof, traf.start + traf.header, traf.end)
            val tfhd = kids.firstOrNull { it.type == "tfhd" } ?: continue
            var at = tfhd.start + tfhd.header
            val flags = u32(moof, at).toInt() and 0xffffff
            val trackId = u32(moof, at + 4).toInt()
            at += 8
            var base = if (flags and 0x20000 != 0) fragment.offset else nextBase
            if (flags and 0x1 != 0) { base = u64(moof, at); at += 8 }
            val description = if (flags and 0x2 != 0) u32(moof, at).toInt().also { at += 4 } else 1
            if (flags and 0x8 != 0) at += 4
            val defaultSize = if (flags and 0x10 != 0) u32(moof, at).toInt() else 0

            // Sample offsets and sizes, in order, from every trun.
            val offsets = ArrayList<Long>()
            val sizes = ArrayList<Int>()
            var cursor = base
            for (trun in kids.filter { it.type == "trun" }) {
                var p = trun.start + trun.header
                val tf = u32(moof, p).toInt() and 0xffffff
                val count = u32(moof, p + 4).toInt()
                p += 8
                if (tf and 0x1 != 0) { cursor = base + u32(moof, p).toInt(); p += 4 }
                if (tf and 0x4 != 0) p += 4
                repeat(count) {
                    if (tf and 0x100 != 0) p += 4
                    val size = if (tf and 0x200 != 0) u32(moof, p).toInt().also { p += 4 } else defaultSize
                    if (tf and 0x400 != 0) p += 4
                    if (tf and 0x800 != 0) p += 4
                    offsets += cursor
                    sizes += size
                    cursor += size
                }
            }
            nextBase = cursor

            val crypto = tracks[trackId]?.takeIf { it.description == description } ?: continue
            kids.filter { it.type == "senc" || it.type == "saiz" || it.type == "saio" }.forEach { renames += fragment.offset + it.start + 4 }
            val senc = kids.firstOrNull { it.type == "senc" }
            if (senc == null) {
                val iv = crypto.constantIv ?: throw CencUnsupported("A fragment has no sample encryption box")
                offsets.indices.forEach { samples += Sample(offsets[it], sizes[it], iv, null, crypto) }
                continue
            }
            var p = senc.start + senc.header
            val sencFlags = u32(moof, p).toInt() and 0xffffff
            val count = u32(moof, p + 4).toInt()
            p += 8
            // A clear lead-in can still have a protected sample entry and an empty senc box.
            if (count == 0) continue
            if (count != offsets.size) throw CencUnsupported("senc lists $count samples, trun ${offsets.size}")
            for (i in 0 until count) {
                val iv = ByteArray(16)
                if (crypto.ivSize > 0) { System.arraycopy(moof, p, iv, 0, crypto.ivSize); p += crypto.ivSize }
                else crypto.constantIv?.let { System.arraycopy(it, 0, iv, 0, minOf(it.size, 16)) }
                var subs: IntArray? = null
                if (sencFlags and 0x2 != 0) {
                    val n = ((moof[p].toInt() and 0xff) shl 8) or (moof[p + 1].toInt() and 0xff)
                    p += 2
                    subs = IntArray(n * 2)
                    for (j in 0 until n) {
                        subs[j * 2] = ((moof[p].toInt() and 0xff) shl 8) or (moof[p + 1].toInt() and 0xff)
                        subs[j * 2 + 1] = u32(moof, p + 2).toInt()
                        p += 6
                    }
                }
                samples += Sample(offsets[i], sizes[i], iv, subs, crypto)
            }
        }
        samples.sortBy { it.offset }
        return Plan(samples, renames)
    }

    companion object {
        private const val FIRST_READ = 65536L
        private const val MOOF_GUESS = 262144L
        private const val PLANS_KEPT = 32
        private val FREE = "free".toByteArray(Charsets.ISO_8859_1)
        private val TOP_LEVEL = setOf("ftyp", "styp", "moov", "moof", "sidx")

        /**
         * The file behind [fetch] (a ranged read) ready to decrypt with [key]; null when it isn't an MP4 at all (a
         * subtitle file). Reads the boxes that say where the media is: the movie header and the segment index, or each
         * fragment's own header for a file without an index.
         */
        fun open(key: ByteArray, fetch: (Long, Long) -> Fetched): CencFile? {
            val first = fetch(0, FIRST_READ - 1)
            val total = first.total
            if (first.bytes.size < 8 || type(first.bytes, 4) !in TOP_LEVEL) return null
            fun read(pos: Long, len: Int): ByteArray =
                if (pos + len <= first.bytes.size) first.bytes.copyOfRange(pos.toInt(), (pos + len).toInt()) else fetch(pos, pos + len - 1).bytes

            val patches = ArrayList<Patch>()
            val tracks = HashMap<Int, TrackCrypto>()
            var fragments = ArrayList<Fragment>()
            var sawMoov = false
            var sawSidx = false
            var pos = 0L
            while (pos + 8 <= total) {
                val h = read(pos, 16)
                if (h.size < 8) break
                var size = u32(h, 0)
                val type = type(h, 4)
                if (size == 1L && h.size >= 16) size = u64(h, 8) else if (size == 0L) size = total - pos
                if (size < 8) break
                when (type) {
                    "moov" -> {
                        val moov = read(pos, size.toInt())
                        if (moov.size < size) throw CencUnsupported("The media host cut the movie header short")
                        parseMoov(moov, pos, patches, tracks)
                        sawMoov = true
                    }
                    "sidx" -> {
                        val sidx = read(pos, size.toInt())
                        if (sidx.size < size) throw CencUnsupported("The media host cut the segment index short")
                        fragments = parseSidx(sidx, pos)
                        sawSidx = true
                    }
                    "moof" -> {
                        val next = read(pos + size, 16)
                        var fragment = size
                        if (next.size >= 8 && type(next, 4) == "mdat") {
                            var mdat = u32(next, 0)
                            if (mdat == 1L && next.size >= 16) mdat = u64(next, 8) else if (mdat == 0L) mdat = total - pos - size
                            fragment += mdat
                        }
                        if (!sawSidx) fragments += Fragment(pos, fragment)
                        pos += fragment
                        continue
                    }
                }
                if (sawSidx && sawMoov) break
                pos += size
            }
            return CencFile(key, patches, tracks, fragments, fetch)
        }

        private fun parseMoov(b: ByteArray, abs: Long, patches: MutableList<Patch>, tracks: MutableMap<Int, TrackCrypto>) {
            val moov = boxes(b, 0, b.size).firstOrNull { it.type == "moov" } ?: return
            for (box in boxes(b, moov.start + moov.header, moov.end)) {
                when (box.type) {
                    "pssh" -> patches += Patch(abs + box.start + 4, FREE)
                    "trak" -> parseTrak(b, abs, box, patches, tracks)
                }
            }
        }

        private fun child(b: ByteArray, parent: Box?, type: String): Box? =
            parent?.let { boxes(b, it.start + it.header, it.end).firstOrNull { c -> c.type == type } }

        private fun parseTrak(b: ByteArray, abs: Long, trak: Box, patches: MutableList<Patch>, tracks: MutableMap<Int, TrackCrypto>) {
            val tkhd = child(b, trak, "tkhd") ?: return
            val at = tkhd.start + tkhd.header
            val trackId = u32(b, at + 4 + if (b[at].toInt() == 1) 16 else 8).toInt()
            val stsd = child(b, child(b, child(b, child(b, trak, "mdia"), "minf"), "stbl"), "stsd") ?: return
            for ((index, entry) in boxes(b, stsd.start + stsd.header + 8, stsd.end).withIndex()) {
                val kidsAt = when (entry.type) {
                    "encv" -> entry.start + entry.header + 78
                    "enca" -> entry.start + entry.header + 28 + when (((b[entry.start + entry.header + 8].toInt() and 0xff) shl 8) or (b[entry.start + entry.header + 9].toInt() and 0xff)) {
                        1 -> 16
                        2 -> 36
                        else -> 0
                    }
                    else -> continue
                }
                val sinf = boxes(b, kidsAt, entry.end).firstOrNull { it.type == "sinf" } ?: continue
                val frma = child(b, sinf, "frma") ?: continue
                val schm = child(b, sinf, "schm")
                val scheme = schm?.let { type(b, it.start + it.header + 4) }
                if (scheme != "cenc" && scheme != "cbcs") throw CencUnsupported("Encryption scheme ${scheme ?: "unknown"}; only cenc and cbcs can be decrypted here")
                val tenc = child(b, child(b, sinf, "schi"), "tenc") ?: throw CencUnsupported("No tenc box")
                val t = tenc.start + tenc.header
                val isProtected = b[t + 6].toInt() == 1
                val ivSize = b[t + 7].toInt() and 0xff
                val constant = if (isProtected && ivSize == 0) b.copyOfRange(t + 25, t + 25 + (b[t + 24].toInt() and 0xff)) else null
                val pattern = if (b[t].toInt() == 1) b[t + 5].toInt() and 0xff else 0
                if (isProtected) tracks[trackId] = TrackCrypto(ivSize, constant, scheme == "cbcs", pattern ushr 4, pattern and 15, index + 1)
                patches += Patch(abs + entry.start + 4, b.copyOfRange(frma.start + frma.header, frma.start + frma.header + 4))
                patches += Patch(abs + sinf.start + 4, FREE)
            }
        }

        private fun parseSidx(b: ByteArray, abs: Long): ArrayList<Fragment> {
            val box = boxes(b, 0, b.size).first { it.type == "sidx" }
            var at = box.start + box.header
            val version = b[at].toInt()
            at += 4 + 8 // version and flags, reference id, timescale
            val firstOffset: Long
            if (version == 0) { firstOffset = u32(b, at + 4); at += 8 } else { firstOffset = u64(b, at + 8); at += 16 }
            val count = ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
            at += 4
            var offset = abs + box.end - box.start + firstOffset
            val out = ArrayList<Fragment>(count)
            repeat(count) {
                val ref = u32(b, at)
                if (ref and 0x80000000L != 0L) throw CencUnsupported("A hierarchical segment index")
                out += Fragment(offset, ref)
                offset += ref
                at += 12
            }
            return out
        }

        private class Box(val type: String, val start: Int, val header: Int, val end: Int)

        private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
            val out = ArrayList<Box>()
            var p = from
            while (p + 8 <= to) {
                var size = u32(b, p)
                var header = 8
                if (size == 1L) {
                    if (p + 16 > to) break
                    size = u64(b, p + 8)
                    header = 16
                } else if (size == 0L) size = (to - p).toLong()
                if (size < header || p + size > to) break
                out += Box(type(b, p + 4), p, header, (p + size).toInt())
                p += size.toInt()
            }
            return out
        }

        private fun type(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)
        private fun u32(b: ByteArray, at: Int) =
            ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
        private fun u64(b: ByteArray, at: Int) = (u32(b, at) shl 32) or u32(b, at + 4)
    }
}
