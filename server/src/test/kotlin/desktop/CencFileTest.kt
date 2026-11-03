package desktop

import desktop.links.CencFile
import desktop.links.CencUnsupported
import desktop.links.Fetched
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CencFileTest {
    private val key = ByteArray(16) { (it * 11 + 3).toByte() }
    private val rnd = Random(7)

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { out -> parts.forEach(out::write) }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private fun ints(vararg v: Int) = ByteBuffer.allocate(v.size * 4).also { b -> v.forEach(b::putInt) }.array()
    private fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()

    /** A sample: its clear bytes, its encrypted form, and how it's split into subsamples (clear, encrypted pairs). */
    private class Sample(val plain: ByteArray, val iv: ByteArray, val subs: List<Pair<Int, Int>>?)

    private fun encrypt(s: Sample): ByteArray {
        val out = s.plain.copyOf()
        val counter = s.iv.copyOf(8) + ByteArray(8)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter)) }
        var at = 0
        for ((clear, enc) in s.subs ?: listOf(0 to s.plain.size)) {
            at += clear
            cipher.update(out, at, enc, out, at)
            at += enc
        }
        return out
    }

    private class Built(val file: ByteArray, val expected: ByteArray)

    private fun build(fragments: List<List<Sample>>): Built {
        val frma = box("frma", "avc1".toByteArray())
        val schm = box("schm", ints(0), "cenc".toByteArray(), ints(0x10000))
        val tenc = box("tenc", ints(0), byteArrayOf(0, 0, 1, 8), ByteArray(16) { 9 })
        val sinf = box("sinf", frma, schm, box("schi", tenc))
        val encv = box("encv", ByteArray(78), sinf)
        val stsd = box("stsd", ints(0, 1), encv)
        val tkhd = box("tkhd", ints(0, 0, 0, 1), ByteArray(68))
        val trak = box("trak", tkhd, box("mdia", box("minf", box("stbl", stsd))))
        val moov = box("moov", box("mvhd", ByteArray(100)), trak, box("pssh", ByteArray(40) { 5 }))
        val ftyp = box("ftyp", "isom".toByteArray(), ints(0), "isom".toByteArray())

        val moofs = ArrayList<ByteArray>()
        val mdats = ArrayList<ByteArray>()
        val mdatBodies = ArrayList<ByteArray>()
        for (samples in fragments) {
            val withSubs = samples.all { it.subs != null }
            val senc = box("senc", ints(if (withSubs) 2 else 0, samples.size), *samples.map { s ->
                s.iv.copyOf(8) + (s.subs?.let { subs -> shorts(subs.size) + subs.map { (c, e) -> shorts(c) + ints(e) }.fold(ByteArray(0)) { a, b -> a + b } } ?: ByteArray(0))
            }.toTypedArray())
            fun moof(dataOffset: Int) = box("moof", box("mfhd", ints(0, 1)), box("traf",
                box("tfhd", ints(0x20000, 1)),
                box("saiz", ints(0), byteArrayOf(8), ints(samples.size)),
                box("saio", ints(0, 1, 0)),
                box("trun", ints(0x201, samples.size, dataOffset), *samples.map { ints(it.plain.size) }.toTypedArray()),
                senc))
            val size = moof(0).size
            moofs += moof(size + 8)
            mdatBodies += samples.fold(ByteArray(0)) { a, s -> a + encrypt(s) }
            mdats += box("mdat", mdatBodies.last())
        }
        val refs = moofs.indices.map { moofs[it].size + mdats[it].size }
        val sidx = box("sidx", ints(0, 1, 1000, 0, 0), shorts(0, refs.size), *refs.map { ints(it, 1000, 0) }.toTypedArray())
        val file = ByteArrayOutputStream().also { o ->
            o.write(ftyp); o.write(moov); o.write(sidx)
            moofs.indices.forEach { o.write(moofs[it]); o.write(mdats[it]) }
        }.toByteArray()

        // What it should come out as: samples in the clear, protection boxes renamed in place.
        val expected = file.copyOf()
        fun rename(from: String, to: String) {
            val at = String(file, Charsets.ISO_8859_1).indexOf(from)
            assertEquals(true, at > 0, from)
            to.toByteArray().copyInto(expected, at)
        }
        rename("encv", "avc1")
        rename("sinf", "free")
        rename("pssh", "free")
        // Every fragment's encryption boxes too.
        val text = String(file, Charsets.ISO_8859_1)
        for (name in listOf("senc", "saiz", "saio")) {
            var at = text.indexOf(name)
            var n = 0
            while (at >= 0) { "free".toByteArray().copyInto(expected, at); n++; at = text.indexOf(name, at + 4) }
            assertEquals(fragments.size, n, name)
        }
        var pos = ftyp.size + moov.size + sidx.size
        for ((i, samples) in fragments.withIndex()) {
            pos += moofs[i].size + 8
            for (s in samples) { s.plain.copyInto(expected, pos); pos += s.plain.size }
        }
        return Built(file, expected)
    }

    private fun samples(sizes: List<Int>, subsamples: Boolean) = sizes.map { n ->
        Sample(rnd.nextBytes(n), rnd.nextBytes(8), if (subsamples) listOf(7 to n / 3, 5 to n - 12 - n / 3) else null)
    }

    private fun open(file: ByteArray) = CencFile.open(key) { from, to ->
        val end = minOf(to, file.size - 1L).toInt()
        Fetched(from, file.copyOfRange(from.toInt(), end + 1), file.size.toLong())
    }

    private fun fragments() = listOf(
        samples(listOf(300, 1021, 64, 777), true),
        samples(listOf(5000, 40, 41), true),
    )

    @Test
    fun decryptsAWholeFileAndRenamesTheProtection() {
        val built = build(fragments())
        val cenc = assertNotNull(open(built.file))
        val out = built.file.copyOf()
        cenc.apply(out, 0, out.size, 0)
        assertContentEquals(built.expected, out)
    }

    @Test
    fun decryptsAnyRangeAndAnyChunking() {
        val built = build(fragments())
        val cenc = assertNotNull(open(built.file))
        // The file read in chunks that cut samples, 16-byte blocks and boxes anywhere.
        val chunked = built.file.copyOf()
        var pos = 0
        while (pos < chunked.size) {
            val n = minOf(rnd.nextInt(1, 90), chunked.size - pos)
            cenc.apply(chunked, pos, n, pos.toLong())
            pos += n
        }
        assertContentEquals(built.expected, chunked)
        // Ranges as a player asks for them, starting mid-sample.
        repeat(40) {
            val a = rnd.nextInt(0, built.file.size - 1)
            val b = rnd.nextInt(a + 1, built.file.size + 1)
            val slice = built.file.copyOfRange(a, b)
            cenc.apply(slice, 0, slice.size, a.toLong())
            assertContentEquals(built.expected.copyOfRange(a, b), slice, "range $a-$b")
        }
    }

    @Test
    fun readsSamplesWithoutSubsamples() {
        val built = build(listOf(samples(listOf(100, 33), false)))
        val cenc = assertNotNull(open(built.file))
        val out = built.file.copyOf()
        cenc.apply(out, 0, out.size, 0)
        assertContentEquals(built.expected, out)
    }

    @Test
    fun leavesWhatIsNotAnMp4AndRefusesOtherSchemes() {
        assertNull(open("WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n".toByteArray()))
        val built = build(fragments())
        val cbcs = built.file.copyOf()
        val at = String(cbcs, Charsets.ISO_8859_1).indexOf("cenc")
        "cbcs".toByteArray().copyInto(cbcs, at)
        assertFailsWith<CencUnsupported> { open(cbcs) }
    }
}
