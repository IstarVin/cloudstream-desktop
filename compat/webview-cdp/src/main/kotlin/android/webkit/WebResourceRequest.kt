package android.webkit

import android.net.Uri

/** A request the page is about to make, as [WebViewClient.shouldInterceptRequest] sees it. */
interface WebResourceRequest {
    fun getUrl(): Uri
    fun isForMainFrame(): Boolean
    fun isRedirect(): Boolean
    fun hasGesture(): Boolean
    fun getMethod(): String
    fun getRequestHeaders(): Map<String, String>
}
