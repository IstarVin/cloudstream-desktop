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
    private class Target(val headers: Map<String, String>, val client: OkHttpClient, val created: Long) {
        val playlists = ConcurrentHashMap<String, String>()
        val aliases = ConcurrentHashMap<String, String>()
    }

    private val targets = ConcurrentHashMap<String, Target>()
    private val random = SecureRandom()

    /** Registers [url]'s request context and returns the proxied URL path (relative to the server root). */
    fun register(url: String, headers: Map<String, String>, interceptor: Interceptor?): String {
        expire()
        val token = ByteArray(12).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val client = interceptor?.let { app.baseClient.newBuilder().addInterceptor(it).build() } ?: app.baseClient
        targets[token] = Target(headers, client, System.currentTimeMillis())
        return path(token, url)
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
        target.playlists[url]?.let { return call.respondText(it, ContentType.parse("application/vnd.apple.mpegurl")) }
        if (url.startsWith("data:", true)) {
            val bytes = dataUri(url) ?: return call.respondText("Malformed data URI", status = HttpStatusCode.BadRequest)
            return call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        val request = Request.Builder().url(url).apply {
            target.headers.forEach { (k, v) -> header(k, v) }
            call.request.header(HttpHeaders.Range)?.let { header(HttpHeaders.Range, it) }
        }.build()

        val res = withContext(Dispatchers.IO) { target.client.newCall(request).execute() }
        // The streaming writer may run after respondOutputStream returns, so it closes the response itself.
        var handedOff = false
        try {
            val type = res.header("Content-Type").orEmpty()
            val body = res.body
            val isPlaylist = type.contains("mpegurl", true) || url.substringBefore('?').endsWith(".m3u8")
            if (isPlaylist && res.isSuccessful) {
                val text = withContext(Dispatchers.IO) { body.string() }
                if (text.trimStart().startsWith("#EXTM3U")) {
                    call.respondText(keep(target, token, url, text, res.request.url.toString()), ContentType.parse("application/vnd.apple.mpegurl"))
                    return
                }
                call.respondBytes(text.toByteArray(), ContentType.parse(type.ifEmpty { "text/plain" }), HttpStatusCode.fromValue(res.code))
                return
            }
            for (h in listOf(HttpHeaders.ContentRange, HttpHeaders.AcceptRanges)) res.header(h)?.let { call.response.header(h, it) }
            val length = body.contentLength().takeIf { it >= 0 }
            handedOff = true
            call.respondOutputStream(
                contentType = runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream),
                status = HttpStatusCode.fromValue(res.code),
                contentLength = length,
            ) {
                // mpv drops connections when it seeks or switches streams; that isn't an error.
                try {
                    withContext(Dispatchers.IO) { body.byteStream().use { it.copyTo(this@respondOutputStream) } }
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
        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
                else -> proxied(line)
            }
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
        private val URI_ATTR = Regex("""URI="([^"]+)"""")

        /** Leaves room under ffmpeg's 4096 for the server address, the token and the file name. */
        private const val MAX_ENCODED = 2048
    }
}
