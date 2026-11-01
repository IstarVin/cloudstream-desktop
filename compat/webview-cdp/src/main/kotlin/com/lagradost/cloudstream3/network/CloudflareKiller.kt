package com.lagradost.cloudstream3.network

import desktop.cdp.Chromium
import desktop.cdp.Clearances
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * The app's CloudflareKiller (same class name and public members), solving challenges in Chromium.
 *
 * A saved clearance is replayed with Chromium's user agent. When a response is a challenge, the check is solved in a
 * Chromium tab and the request retried with the cookies it earned; if a replayed clearance is refused, it is solved
 * again once. Unlike the app's, it never sends a second request through OkHttp from inside the interceptor, so it
 * can't deadlock OkHttp's per-host slots. If the check can't be passed, the challenge response is returned as is, as
 * the app does, and the check is listed for a manual solve.
 */
class CloudflareKiller : Interceptor {
    /** Shared by every instance and saved to disk (see [Clearances]). */
    val savedCookies: MutableMap<String, Map<String, String>> = Clearances.hosts

    fun getCookieHeaders(url: String): Headers = headers(savedCookies[URI(url).host].orEmpty())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val saved = savedCookies[request.url.host]
        val response = chain.proceed(if (saved != null) withClearance(request, saved) else request)
        if (!isChallenge(response)) return response
        response.close()
        val cookies = runBlocking {
            Chromium.solveCloudflare(request.url.toString(), saved) { chain.call().isCanceled() }
        } ?: return chain.proceed(request)
        return chain.proceed(withClearance(request, cookies))
    }

    private fun withClearance(request: Request, cookies: Map<String, String>): Request {
        val all = request.header("Cookie")?.let(::parseCookieMap).orEmpty() + cookies
        return request.newBuilder().apply {
            header("Cookie", all.entries.joinToString("; ") { "${it.key}=${it.value}" })
            WebViewResolver.webViewUserAgent?.let { header("User-Agent", it) }
        }.build()
    }

    private fun headers(cookies: Map<String, String>): Headers = buildMap {
        WebViewResolver.webViewUserAgent?.let { put("user-agent", it) }
        if (cookies.isNotEmpty()) put("cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }.toHeaders()

    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(";").mapNotNull {
                val split = it.split("=", limit = 2)
                if (split.size == 2) split[0].trim() to split[1].trim() else null
            }.toMap()

        fun isChallenge(response: Response) =
            response.code in ERROR_CODES &&
                (response.header("Server")?.lowercase() in CLOUDFLARE_SERVERS || response.header("cf-mitigated") == "challenge")
    }
}
