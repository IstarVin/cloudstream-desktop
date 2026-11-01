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
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * `/proxy/{token}/{b64url}`: fetches through the plugins' own OkHttp client with the link's headers and the
 * provider's video interceptor, for links mpv can't fetch by itself. HLS playlists are rewritten so every segment,
 * key and variant URI also goes through the proxy with the same token.
 */
class StreamProxy {
    private class Target(val headers: Map<String, String>, val client: OkHttpClient, val created: Long)

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
        val name = URI(url).path.substringAfterLast('/').takeIf { it.contains('.') }?.let { "/" + it.take(60) } ?: ""
        return "/proxy/$token/" + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray()) + name
    }

    suspend fun serve(call: ApplicationCall, token: String, encoded: String) {
        val target = targets[token] ?: return call.respondText("Unknown proxy token", status = HttpStatusCode.NotFound)
        val url = String(Base64.getUrlDecoder().decode(encoded))
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
                    call.respondText(rewrite(text, res.request.url.toString(), token), ContentType.parse("application/vnd.apple.mpegurl"))
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

    /** Points every URI in an HLS playlist (plain lines and `URI="…"` attributes) back at this proxy. */
    fun rewrite(playlist: String, base: String, token: String): String {
        val baseUri = URI(base)
        fun proxied(ref: String) = path(token, baseUri.resolve(ref.trim()).toString())
        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - 12 * 3600_000L
        targets.entries.removeIf { it.value.created < cutoff }
    }

    companion object {
        private val URI_ATTR = Regex("""URI="([^"]+)"""")
    }
}
