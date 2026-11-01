package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.USER_AGENT
import desktop.cdp.Chromium
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The desktop WebViewResolver: the library's class name, constructor and methods (its JVM build is a `TODO()`),
 * backed by a Chromium tab. The server's classpath puts this jar ahead of library-jvm, so plugins link against it.
 *
 * Like the app, it loads the page and watches the requests it makes: the first URL matching [interceptUrl] ends the
 * resolve, and URLs matching [additionalUrls] are collected (ending it early when `requestCallBack` returns true).
 */
class WebViewResolver(
    private val interceptUrl: Regex,
    private val additionalUrls: List<Regex> = emptyList(),
    private val userAgent: String? = USER_AGENT,
    @Suppress("unused") private val useOkhttp: Boolean = true,
    private val script: String? = null,
    private val scriptCallback: ((String) -> Unit)? = null,
    private val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fixed = runBlocking { resolveUsingWebView(request).first }
        return chain.proceed(fixed ?: request)
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            referer?.let { header("Referer", it) }
            if (method != "GET") method(method, if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null)
        }.build()
        return resolveUsingWebView(request, requestCallBack)
    }

    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> =
        Chromium.resolve(request, interceptUrl, additionalUrls, userAgent, script, scriptCallback, timeout, requestCallBack)

    companion object {
        /** Marks this class as the desktop one; the server checks for it at startup. */
        const val DESKTOP_IMPLEMENTATION = true

        val DEFAULT_TIMEOUT = 60_000L

        /** Chromium's user agent, which cookies earned in the browser are tied to. Null until Chromium has run once. */
        var webViewUserAgent: String? = null
    }
}
