package desktop.links

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Serves identity SAMPLE-AES fMP4 as clear media for players that cannot use that HLS key format. */
internal class HlsDecryptor(private val read: (String) -> ByteArray) {
    private data class Section(val init: String, val key: String)
    private val sections = ConcurrentHashMap<String, Section>()
    private val inits = ConcurrentHashMap<String, ByteArray>()
    private val keys = ConcurrentHashMap<String, ByteArray>()

    /** Only remove key tags when every encrypted segment in this playlist has a supported init/key context. */
    fun prepare(playlist: String, base: String): String {
        val lines = playlist.lines()
        if (lines.none { it.startsWith("#EXT-X-KEY:") }) return playlist
        val pending = LinkedHashMap<String, Section>()
        var init: String? = null
        var key: String? = null
        var encrypted = false
        fun uri(line: String) = URI_ATTR.find(line)?.groupValues?.get(1)?.let { URI(base).resolve(it).toString() }
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-BYTERANGE:") -> return playlist
                line.startsWith("#EXT-X-MAP:") -> {
                    if ("BYTERANGE=" in line) return playlist
                    init = uri(line) ?: return playlist
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    if (line.substringAfter(':') == "METHOD=NONE") { key = null; continue }
                    if (!SAMPLE_AES.containsMatchIn(line) ||
                        ("KEYFORMAT=" in line && "KEYFORMAT=\"identity\"" !in line)) return playlist
                    key = uri(line) ?: return playlist
                    encrypted = true
                }
                line.isNotBlank() && !line.startsWith('#') && key != null -> {
                    val section = Section(init ?: return playlist, key)
                    val url = URI(base).resolve(line.trim()).toString()
                    pending[url] = section
                    pending[section.init] = section
                }
            }
        }
        if (!encrypted || pending.isEmpty()) return playlist
        sections.putAll(pending)
        return lines.filterNot { it.startsWith("#EXT-X-KEY:") }.joinToString("\n")
    }

    fun decrypt(url: String): ByteArray? {
        val section = sections[url] ?: return null
        val key = keys.computeIfAbsent(section.key) {
            read(it).also { bytes -> require(bytes.size == 16) { "SAMPLE-AES needs a 16-byte key" } }
        }
        val init = inits.computeIfAbsent(section.init, read)
        // MP4 fragment offsets are relative to the moof. Joining the original init and segment lets the same
        // parser handle both standalone HLS fragments and indexed DASH files, preserving every byte offset.
        val bytes = if (url == section.init) init.copyOf() else init + read(url)
        val source = bytes.copyOf()
        val file = CencFile.open(key) { from, to ->
            Fetched(from, source.copyOfRange(from.toInt(), minOf(to + 1, source.size.toLong()).toInt()), source.size.toLong())
        } ?: throw CencUnsupported("SAMPLE-AES initialization is not an MP4")
        file.apply(bytes, 0, bytes.size, 0)
        return if (url == section.init) bytes else bytes.copyOfRange(init.size, bytes.size)
    }

    companion object {
        private val URI_ATTR = Regex("""\bURI="([^"]+)"""")
        private val SAMPLE_AES = Regex("""(?:[:,])METHOD=SAMPLE-AES(?:,|$)""")
    }
}
