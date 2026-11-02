package desktop.links

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import desktop.api.ClearKeyDto
import desktop.api.NotFound
import desktop.api.StreamDto
import desktop.api.SubtitleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI

/** A link's request headers the way players take them: User-Agent and Referer apart from the rest. */
class LinkHeaders(val userAgent: String, val referer: String?, val extra: Map<String, String>) {
    /** Every header in one map, for the proxy or a player that takes them that way. */
    val all: Map<String, String> get() = extra + listOfNotNull(referer?.let { "Referer" to it }, "User-Agent" to userAgent)

    companion object {
        fun of(link: ExtractorLink): LinkHeaders {
            val headers = link.headers.toMutableMap()
            val ua = headers.keys.firstOrNull { it.equals("User-Agent", true) }?.let(headers::remove) ?: USER_AGENT
            val referer = link.referer.takeIf { it.isNotBlank() } ?: headers.keys.firstOrNull { it.equals("Referer", true) }?.let(headers::remove)
            headers.keys.filter { it.equals("Referer", true) }.forEach(headers::remove)
            return LinkHeaders(ua, referer, headers)
        }
    }
}

/**
 * Links and subtitles for a client's own player (the Android app), as opposed to mpv on this machine. Streams go
 * through [StreamProxy] unless the client asks for the direct URL and nothing about the link needs the proxy: the
 * proxy applies the provider's interceptor and reaches plugin servers bound to this machine's loopback.
 */
class ClientStreams(
    private val links: LinkService,
    private val proxy: StreamProxy,
    private val http: () -> OkHttpClient = { app.baseClient },
) {
    class Gone(message: String) : RuntimeException(message)

    fun stream(linkId: String, direct: Boolean, proxyAll: Boolean): StreamDto {
        val link = links.link(linkId) ?: throw Gone("Those links have expired; load them again")
        require(link.playable) { "This link can't be played" }
        val l = link.link
        val headers = LinkHeaders.of(l)
        val clearKey = link.clearKey?.let { ClearKeyDto((l as? DrmExtractorLink)?.kid?.let(ClearKey::toHex), it) }
        if (direct && !proxyAll && !needsProxy(link)) {
            return StreamDto(l.url, l.type.name, headers.all, proxied = false, clearKey = clearKey)
        }
        return StreamDto(proxy.register(l.url, headers.all, link.interceptor), l.type.name, proxied = true, clearKey = clearKey)
    }

    private fun needsProxy(link: ResolvedLink): Boolean {
        if (link.proxied) return true
        val host = runCatching { URI(link.link.url).host }.getOrNull() ?: return true
        return host == "localhost" || runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    /** The subtitle's text and its format, fetched with the headers the provider gave it. */
    suspend fun subtitle(id: String): Pair<SubtitleFormat, ByteArray> {
        val sub = links.subtitle(id)?.sub ?: throw NotFound("Unknown subtitle $id")
        val request = Request.Builder().url(sub.url).apply {
            header("User-Agent", USER_AGENT)
            sub.headers?.forEach { (k, v) -> header(k, v) }
        }.build()
        val bytes = withContext(Dispatchers.IO) {
            http().newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("The subtitle host answered ${res.code}")
                res.body.bytes()
            }
        }
        return SubtitleFormat.sniff(String(bytes.copyOf(minOf(bytes.size, 1024))), sub.url) to bytes
    }
}
