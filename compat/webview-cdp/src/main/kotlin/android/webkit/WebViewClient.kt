package android.webkit

/**
 * Hooks into a [WebView]'s loading. The desktop calls [shouldInterceptRequest] for every request of the page, its
 * iframes and its workers, [shouldOverrideUrlLoading] for top-frame navigations other than [WebView.loadUrl]'s, and
 * [onPageFinished] when the top frame has loaded.
 */
open class WebViewClient {
    open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        @Suppress("DEPRECATION") shouldInterceptRequest(view, request.getUrl().toString())

    @Deprecated("Use shouldInterceptRequest(WebView, WebResourceRequest)")
    open fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? = null

    open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        @Suppress("DEPRECATION") shouldOverrideUrlLoading(view, request.getUrl().toString())

    @Deprecated("Use shouldOverrideUrlLoading(WebView, WebResourceRequest)")
    open fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

    open fun onPageFinished(view: WebView, url: String) {}
}
