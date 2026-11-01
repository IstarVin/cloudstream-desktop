package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * v1 placeholder for the app's CloudflareKiller (same class name and public signatures).
 *
 * Requests go through unchanged. When a response is a Cloudflare challenge, it fails with [ChromiumRequiredException]
 * so only the sources that really need a browser fail, and the server can say why. v1.1 solves the challenge in
 * Chromium over CDP and retries with the `cf_clearance` cookie.
 */
class CloudflareKiller : Interceptor {
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers = Headers.Builder().build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code in ERROR_CODES && response.header("Server") in CLOUDFLARE_SERVERS) {
            response.close()
            throw ChromiumRequiredException("${chain.request().url.host} is behind a Cloudflare challenge")
        }
        return response
    }

    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(";").mapNotNull {
                val split = it.split("=", limit = 2)
                if (split.size == 2) split[0].trim() to split[1].trim() else null
            }.toMap()
    }
}

/** Thrown where v1 would need Chromium (Cloudflare challenges, WebView resolving). Shown per source in the UI. */
class ChromiumRequiredException(reason: String) : IOException("$reason; needs Chromium support (v1.1)")
