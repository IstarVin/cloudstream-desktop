package desktop.links

import com.lagradost.cloudstream3.app
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * `/proxy/{token}/{b64url}`: fetches through the plugins' own OkHttp client with the link's headers and the
 * provider's video interceptor, for links mpv can't fetch by itself. HLS playlists are rewritten so every segment,
 * key and variant URI also goes through the proxy with the same token.
 *
 * ffmpeg cuts URLs at 4096 bytes, and some upstream URLs (GoPlay's, about 4.5 KB) are that long before encoding. A
 * URL too long to carry in the path is kept on its token and named by a short alias, `/proxy/{token}/~{alias}`.
 *
 * mpv fetches a master playlist twice (its own probe, then libavformat opens it again), and some sources (GoPlay)
 * hand out playlist URLs that work only once. Playlists that can't change, masters and VOD media playlists, are
 * kept per token so a repeat request never reaches the upstream.
 */
class StreamProxy {
    private class Target(val headers: Map<String, String>, val client: OkHttpClient, val created: Long, val key: ByteArray? = null) {
        /** The decryptor of each media file fetched here with a [key]; empty for a file that isn't an MP4. */
        val cenc = ConcurrentHashMap<String, java.util.Optional<CencFile>>()
        val playlists = ConcurrentHashMap<String, String>()
        val aliases = ConcurrentHashMap<String, String>()
        /** DASH manifests made here (one per period of a multi-period one), keyed by their made-up URL. */
        val documents = ConcurrentHashMap<String, String>()
        /** Subtitles read from the Matroska files fetched here, by URL; [notMatroska] for files found not to be one. */
        val matroska = ConcurrentHashMap<String, MkvSubtitles>()
        val notMatroska: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val targets = ConcurrentHashMap<String, Target>()
    private val random = SecureRandom()

    /** Registers [url]'s request context and returns the proxied URL path (relative to the server root). */
    fun register(url: String, headers: Map<String, String>, interceptor: Interceptor?, key: ByteArray? = null): String {
        expire()
        val token = ByteArray(12).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val client = interceptor?.let { app.baseClient.newBuilder().addInterceptor(it).build() } ?: app.baseClient
        targets[token] = Target(headers, client, System.currentTimeMillis(), key)
        return path(token, url)
    }

    /**
     * The proxied path of the ClearKey DASH manifest at [url], with the proxy decrypting every media file it serves
     * with [key] and the manifest rewritten to say so (no protection, files through the proxy). Null when the manifest
     * isn't one this can do that for ([decryptableMpd]); nothing stays registered then.
     */
    suspend fun registerDecrypted(url: String, headers: Map<String, String>, interceptor: Interceptor?, key: ByteArray): String? {
        val path = register(url, headers, interceptor, key)
        val token = path.removePrefix("/proxy/").substringBefore('/')
        val target = targets.getValue(token)
        val rewritten = runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                target.client.newCall(request).execute().use { res ->
                    val text = res.body.string().takeIf { res.isSuccessful && it.trimStart().startsWith("<") } ?: return@use null
                    decryptableMpd(text, res.request.url.toString(), token)
                }
            }
        }.getOrNull()
        if (rewritten == null) { targets.remove(token); return null }
        target.documents[url] = rewritten
        return path
    }

    /** A decryptor for the MP4 at [url], opened once; null when it isn't an MP4. */
    private fun cencFor(target: Target, url: String): CencFile? {
        val key = target.key ?: return null
        target.cenc[url]?.let { return it.orElse(null) }
        val file = CencFile.open(key) { from, to ->
            val request = Request.Builder().url(url).apply {
                target.headers.forEach { (k, v) -> header(k, v) }
                header(HttpHeaders.Range, "bytes=$from-$to")
            }.build()
            target.client.newCall(request).execute().use { res ->
                if (res.code != 206) throw IOException("The media host answered ${res.code} to a ranged read; decrypting needs ranges")
                val total = res.header(HttpHeaders.ContentRange)?.substringAfter('/')?.toLongOrNull() ?: throw IOException("No file length")
                Fetched(from, res.body.bytes(), total)
            }
        }
        target.cenc[url] = java.util.Optional.ofNullable(file)
        return file
    }

    /**
     * [mpd] for a client that gets the media decrypted by this proxy: each `BaseURL` points back here and the
     * protection elements are gone. Null for what this can't serve that way: `cenc` is the only scheme handled, and
     * media named by a template or a segment list isn't one file with a segment index.
     */
    fun decryptableMpd(mpd: String, base: String, token: String): String? {
        if (BASE_URL.find(mpd) == null || "<SegmentList" in mpd || TEMPLATE_ATTR.containsMatchIn(mpd)) return null
        val scheme = CENC_PROTECTION_ANY.findAll(mpd).mapNotNull { Regex("""\bvalue="([^"]*)"""").find(it.value)?.groupValues?.get(1) }.firstOrNull()
        if (scheme != null && scheme != "cenc") return null
        val baseUri = URI(base)
        val rebased = BASE_URL.replace(mpd) { m ->
            val uri = baseUri.resolve(m.groupValues[1].trim().replace("&amp;", "&"))
            if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return@replace m.value
            "<BaseURL>${path(token, uri.toString()).replace("&", "&amp;")}</BaseURL>"
        }
        return PROTECTION_ELEMENT.replace(rebased, "")
    }

    private fun path(token: String, url: String): String {
        val name = runCatching { URI(url).path }.getOrNull().orEmpty().substringAfterLast('/').takeIf { it.contains('.') }?.let { "/" + it.take(60) } ?: ""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())
        val target = targets[token]
        if (encoded.length <= MAX_ENCODED || target == null) return "/proxy/$token/$encoded$name"
        val alias = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it).take(22) }
        target.aliases[alias] = url
        return "/proxy/$token/~$alias$name"
    }

    private fun url(target: Target, encoded: String): String? =
        if (encoded.startsWith("~")) target.aliases[encoded.drop(1)] else String(Base64.getUrlDecoder().decode(encoded))

    suspend fun serve(call: ApplicationCall, token: String, encoded: String) {
        val target = targets[token] ?: return call.respondText("Unknown proxy token", status = HttpStatusCode.NotFound)
        val url = url(target, encoded) ?: return call.respondText("Unknown proxy alias", status = HttpStatusCode.NotFound)
        target.documents[url]?.let { return respondDocument(call, it) }
        target.playlists[url]?.let { return call.respondText(it, ContentType.parse("application/vnd.apple.mpegurl")) }
        if (url.startsWith("data:", true)) {
            val bytes = dataUri(url) ?: return call.respondText("Malformed data URI", status = HttpStatusCode.BadRequest)
            return call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        val request = Request.Builder().url(url).apply {
            target.headers.forEach { (k, v) -> header(k, v) }
            // A manifest is rewritten whole, so it's fetched whole; [respondDocument] answers the range.
            if (!url.substringBefore('?').endsWith(".mpd")) call.request.header(HttpHeaders.Range)?.let { header(HttpHeaders.Range, it) }
        }.build()

        val res = withContext(Dispatchers.IO) { target.client.newCall(request).execute() }
        // The streaming writer may run after respondOutputStream returns, so it closes the response itself.
        var handedOff = false
        try {
            val type = res.header("Content-Type").orEmpty()
            val body = res.body
            // A manifest is a manifest whatever the CDN labels it (GoPlay's .mpd comes as mpegurl).
            val isManifest = type.contains("dash+xml", true) || url.substringBefore('?').endsWith(".mpd")
            val isPlaylist = !isManifest && (type.contains("mpegurl", true) || url.substringBefore('?').endsWith(".m3u8"))
            if (isPlaylist && res.isSuccessful) {
                // Some CDNs label every file mpegurl, init segments included; those bytes must not go through a charset.
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                if (bytes.take(PLAYLIST_SNIFF).toByteArray().decodeToString().trimStart().startsWith("#EXTM3U")) {
                    call.respondText(keep(target, token, url, bytes.decodeToString(), res.request.url.toString()), ContentType.parse("application/vnd.apple.mpegurl"))
                    return
                }
                call.respondBytes(bytes, runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream), HttpStatusCode.fromValue(res.code))
                return
            }
            if (res.isSuccessful && isManifest) {
                val text = withContext(Dispatchers.IO) { body.string() }
                respondDocument(call, rewriteMpd(text, res.request.url.toString(), token))
                return
            }
            for (h in listOf(HttpHeaders.ContentRange, HttpHeaders.AcceptRanges)) res.header(h)?.let { call.response.header(h, it) }
            val length = body.contentLength().takeIf { it >= 0 }
            val cenc = if (target.key != null && res.isSuccessful) {
                try { withContext(Dispatchers.IO) { cencFor(target, url) } }
                catch (e: Exception) { return call.respondText("Can't decrypt this stream: ${e.message}", status = HttpStatusCode.BadGateway) }
            } else null
            var position = res.header(HttpHeaders.ContentRange)?.let { CONTENT_RANGE.find(it) }?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val tap = if (res.isSuccessful) tap(target, url, res.header(HttpHeaders.ContentRange)) else null
            handedOff = true
            call.respondOutputStream(
                contentType = runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream),
                status = HttpStatusCode.fromValue(res.code),
                contentLength = length,
            ) {
                // mpv drops connections when it seeks or switches streams; that isn't an error.
                try {
                    withContext(Dispatchers.IO) {
                        body.byteStream().use { input ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(chunk)
                                if (n < 0) break
                                cenc?.apply(chunk, 0, n, position)
                                position += n
                                this@respondOutputStream.write(chunk, 0, n)
                                tap?.feed(chunk, 0, n)
                            }
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    res.close()
                }
            }
        } finally {
            if (!handedOff) res.close()
        }
    }

    /**
     * A reader for the subtitles of a Matroska file in a response starting at [contentRange]'s first byte (the file's
     * start without one). From the start it finds out whether the file is Matroska at all; from elsewhere it reads only
     * a file already known to be one.
     */
    private fun tap(target: Target, url: String, contentRange: String?): MkvTap? {
        val start = contentRange?.let { CONTENT_RANGE.find(it) }?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (start == 0L) {
            if (url in target.notMatroska) return null
            return MkvTap(0) { isMatroska ->
                if (isMatroska) target.matroska.getOrPut(url) { MkvSubtitles() }
                else { target.notMatroska.add(url); null }
            }
        }
        val subs = target.matroska[url] ?: return null
        return MkvTap(start) { subs }
    }

    /**
     * The subtitles read so far from the file behind [path] (from [register]): null while it's unknown whether the file
     * is Matroska, [NOT_MATROSKA] when it isn't.
     */
    fun embeddedSubtitles(path: String): MkvSubtitles? {
        val (token, encoded) = path.substringAfter("/proxy/").split('/').takeIf { it.size >= 2 } ?: return NOT_MATROSKA
        val target = targets[token] ?: return NOT_MATROSKA
        val url = runCatching { url(target, encoded) }.getOrNull() ?: return NOT_MATROSKA
        return target.matroska[url] ?: if (url in target.notMatroska) NOT_MATROSKA else null
    }

    /**
     * Points every URI in an HLS playlist (plain lines and `URI="…"` attributes) back at this proxy. That includes
     * inline `data:` keys (GoPlay's SAMPLE-AES ones), which ffmpeg refuses to open itself; [serve] answers those.
     */
    fun rewrite(playlist: String, base: String, token: String): String {
        val baseUri = URI(base)
        fun proxied(ref: String): String {
            if (ref.trim().startsWith("data:", true)) return path(token, ref.trim())
            val uri = baseUri.resolve(ref.trim())
            return if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) path(token, uri.toString()) else ref
        }
        val textSubs = hasTextSubtitles(playlist)
        return playlist.lineSequence().filterNot { textSubs && isTextSubtitle(it) }.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                textSubs && "#EXT-X-STREAM-INF" in line -> SUBTITLES_ATTR.replace(line, "")
                line.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }

    /**
     * Whether the master playlist at [url] lists subtitles as bare `.vtt`/`.srt` files. ffmpeg opens every such
     * rendition as an HLS playlist, fails on each, and then fails the video variants with them, so mpv plays audio only.
     * Those links go through the proxy, which drops the renditions (the subtitles reach mpv as external files anyway).
     */
    suspend fun hasTextSubtitles(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            app.baseClient.newCall(req).execute().use { res ->
                res.isSuccessful && hasTextSubtitles(res.body.string())
            }
        }.getOrDefault(false)
    }

    /**
     * A DASH manifest built here. FFmpeg probes a manifest, then seeks back and re-reads it with a `Range` request, so
     * without range support it would get the document twice over.
     */
    private suspend fun respondDocument(call: ApplicationCall, text: String) {
        val bytes = text.toByteArray()
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val range = call.request.header(HttpHeaders.Range)?.let { RANGE.matchEntire(it.trim()) }
        val type = ContentType.parse("application/dash+xml")
        if (range == null) return call.respondBytes(bytes, type)
        val from = range.groupValues[1].toInt()
        val to = range.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
        if (from > to) {
            call.response.header(HttpHeaders.ContentRange, "bytes */${bytes.size}")
            return call.respondBytes(ByteArray(0), type, HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        call.response.header(HttpHeaders.ContentRange, "bytes $from-$to/${bytes.size}")
        call.respondBytes(bytes.copyOfRange(from, to + 1), type, HttpStatusCode.PartialContent)
    }

    /**
     * Points the explicit segment URLs of a DASH manifest (`SegmentURL@media`, `Initialization@sourceURL`) back at this
     * proxy, so they carry the link's headers. Templates (`$Number$`) are left alone.
     */
    fun rewriteMpd(mpd: String, base: String, token: String): String {
        val baseUri = URI(base)
        return MPD_URL.replace(withClearKeyProtection(withSegmentDuration(mpd))) { m ->
            val ref = m.groupValues[2].replace("&amp;", "&")
            if ('$' in ref) return@replace m.value
            val uri = baseUri.resolve(ref)
            if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return@replace m.value
            """${m.groupValues[1]}="${path(token, uri.toString()).replace("&", "&amp;")}""""
        }
    }

    /**
     * For a multi-period DASH manifest behind [path] (from [register]), one proxied single-period manifest per period,
     * in order; null when it isn't one. FFmpeg's DASH demuxer plays only the first `<Period>`, so mpv joins these
     * (`edl://`) instead.
     */
    suspend fun periodPaths(path: String): List<String>? = withContext(Dispatchers.IO) {
        val (token, encoded) = path.removePrefix("/proxy/").split('/').takeIf { it.size >= 2 } ?: return@withContext null
        val target = targets[token] ?: return@withContext null
        val url = url(target, encoded) ?: return@withContext null
        val request = Request.Builder().url(url).apply { target.headers.forEach { (k, v) -> header(k, v) } }.build()
        val mpd = target.client.newCall(request).execute().use { res ->
            res.body.string().takeIf { res.isSuccessful && it.trimStart().startsWith("<?xml") }?.let { it to res.request.url.toString() }
        } ?: return@withContext null
        val parts = splitPeriods(mpd.first)?.takeIf { it.size > 1 } ?: return@withContext null
        parts.mapIndexed { i, part ->
            val name = "mpd:///period-$i.mpd"
            target.documents[name] = rewriteMpd(part, mpd.second, token)
            path(token, name)
        }
    }

    /** [playlist] rewritten, and kept when it's a master or a complete media playlist (a live one changes on every reload). */
    private fun keep(target: Target, token: String, url: String, playlist: String, finalUrl: String): String {
        val rewritten = rewrite(playlist, finalUrl, token)
        if ("#EXT-X-STREAM-INF" in playlist || "#EXT-X-ENDLIST" in playlist) target.playlists[url] = rewritten
        return rewritten
    }

    /**
     * The hex key of a SAMPLE-AES fMP4 stream behind [path] (from [register]), or null when it isn't one. ffmpeg's HLS
     * demuxer loads such a key but never hands it to the fMP4 demuxer, so mpv needs it as an option up front.
     * Reading the playlists here keeps them, so mpv's own requests don't reach the upstream again.
     */
    suspend fun sampleAesKey(path: String): String? = withContext(Dispatchers.IO) {
        val (token, encoded) = path.removePrefix("/proxy/").split('/').takeIf { it.size >= 2 } ?: return@withContext null
        val target = targets[token] ?: return@withContext null

        fun get(url: String) = Request.Builder().url(url).apply { target.headers.forEach { (k, v) -> header(k, v) } }.build()
            .let { target.client.newCall(it).execute() }

        fun playlist(encoded: String): String? {
            val url = url(target, encoded) ?: return null
            target.playlists[url]?.let { return it }
            return get(url).use { res ->
                val text = res.body.string().takeIf { res.isSuccessful && it.trimStart().startsWith("#EXTM3U") } ?: return null
                keep(target, token, url, text, res.request.url.toString())
            }
        }

        fun encodedOf(proxied: String) = proxied.removePrefix("/proxy/$token/").substringBefore('/')

        var media = playlist(encoded) ?: return@withContext null
        if ("#EXT-X-STREAM-INF" in media) {
            val variant = media.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@withContext null
            media = playlist(encodedOf(variant)) ?: return@withContext null
        }
        if ("#EXT-X-MAP" !in media) return@withContext null
        val keyLine = media.lines().firstOrNull { it.startsWith("#EXT-X-KEY") && "METHOD=SAMPLE-AES," in it } ?: return@withContext null
        val keyUrl = URI_ATTR.find(keyLine)?.groupValues?.get(1)?.let { url(target, encodedOf(it)) } ?: return@withContext null
        val key = if (keyUrl.startsWith("data:", true)) dataUri(keyUrl) else get(keyUrl).use { if (it.isSuccessful) it.body.bytes() else null }
        key?.takeIf { it.size == 16 }?.joinToString("") { "%02x".format(it) }
    }

    private fun dataUri(url: String): ByteArray? {
        val (meta, data) = url.removeRange(0, 5).split(',', limit = 2).takeIf { it.size == 2 } ?: return null
        return if (meta.endsWith(";base64", true)) Base64.getMimeDecoder().decode(data) else URLDecoder.decode(data.replace("+", "%2B"), Charsets.UTF_8).toByteArray()
    }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - 12 * 3600_000L
        targets.entries.removeIf { it.value.created < cutoff }
    }

    companion object {
        private const val PLAYLIST_SNIFF = 16

        /** What [embeddedSubtitles] answers for a stream that isn't a Matroska file. */
        val NOT_MATROSKA = MkvSubtitles()
        private val CONTENT_RANGE = Regex("""bytes (\d+)-""")

        private val SUBTITLES_ATTR = Regex(""",SUBTITLES="[^"]*"""")
        private val TEXT_SUBTITLE = Regex("""URI="[^"?]+\.(vtt|srt)(\?[^"]*)?"""", RegexOption.IGNORE_CASE)

        private fun isTextSubtitle(line: String) =
            line.startsWith("#EXT-X-MEDIA") && "TYPE=SUBTITLES" in line && TEXT_SUBTITLE.containsMatchIn(line)
        fun hasTextSubtitles(playlist: String) = playlist.lineSequence().any(::isTextSubtitle)

        private val RANGE = Regex("""bytes=(\d+)-(\d*)""")
        private val SEGMENT_LIST = Regex("""<SegmentList\b([^>]*)>(.*?)</SegmentList>""", RegexOption.DOT_MATCHES_ALL)
        private val TIMELINE_D = Regex("""<S\b[^>]*\sd="(\d+)"""")

        /**
         * FFmpeg ignores a `SegmentTimeline` inside a `SegmentList` and can't seek without a `duration`, so mpv would
         * read on from the start to reach a position. Gives each list the most common segment length of its timeline.
         */
        fun withSegmentDuration(mpd: String): String = SEGMENT_LIST.replace(mpd) { m ->
            val attrs = m.groupValues[1]
            val d = TIMELINE_D.findAll(m.groupValues[2]).map { it.groupValues[1] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (d == null || Regex("""\sduration=""").containsMatchIn(attrs)) m.value
            else "<SegmentList$attrs duration=\"$d\">${m.groupValues[2]}</SegmentList>"
        }

        private val BASE_URL = Regex("""<BaseURL\b[^>]*>([^<]+)</BaseURL>""")
        private val PROTECTION_ELEMENT = Regex("""<ContentProtection\b[^>]*/>|<ContentProtection\b[^>]*>.*?</ContentProtection>""", RegexOption.DOT_MATCHES_ALL)
        private val CENC_PROTECTION_ANY = Regex("""<ContentProtection\b[^>]*mp4protection:2011[^>]*>""")
        private val TEMPLATE_ATTR = Regex("""\b(media|initialization)="[^"]*[$]""")
        private const val CLEARKEY_SCHEME = "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e"
        private val CENC_PROTECTION = Regex("""<ContentProtection\b[^>]*mp4protection:2011[^>]*/>""")

        /**
         * Players decrypting with a ClearKey only use the streams whose manifest names that key system: dash.js drops a
         * manifest listing just Widevine and PlayReady ("No streams to play"). Where the manifest has the generic
         * `cenc` protection element (key id, no scheme of its own) and no ClearKey one, adds the ClearKey element.
         */
        fun withClearKeyProtection(mpd: String): String {
            if (CLEARKEY_SCHEME in mpd) return mpd
            return CENC_PROTECTION.replace(mpd) { it.value + """<ContentProtection schemeIdUri="$CLEARKEY_SCHEME"/>""" }
        }

        private val PERIOD = Regex("""<Period\b.*?</Period>""", RegexOption.DOT_MATCHES_ALL)
        private val START = Regex("""\sstart="[^"]*"""")
        private val DURATION = Regex("""\sduration="([^"]*)"""")
        private val PRESENTATION = Regex("""mediaPresentationDuration="[^"]*"""")

        /** [mpd] as one manifest per `<Period>`, each starting at zero; null without any. */
        fun splitPeriods(mpd: String): List<String>? {
            val periods = PERIOD.findAll(mpd).toList().takeIf { it.isNotEmpty() } ?: return null
            val head = mpd.substring(0, periods.first().range.first)
            val tail = mpd.substring(periods.last().range.last + 1)
            return periods.map { m ->
                val period = START.replace(m.value, "")
                val duration = DURATION.find(period.substringBefore('>'))?.groupValues?.get(1)
                val h = if (duration != null) PRESENTATION.replace(head, "mediaPresentationDuration=\"$duration\"") else head
                h + period + tail
            }
        }

        private val URI_ATTR = Regex("""URI="([^"]+)"""")
        private val MPD_URL = Regex("""\b(media|sourceURL)="([^"]+)"""")

        /** Leaves room under ffmpeg's 4096 for the server address, the token and the file name. */
        private const val MAX_ENCODED = 2048
    }
}
