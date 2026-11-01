package android.webkit

import desktop.cdp.Chromium

/**
 * A WebView's settings. Chromium already runs JavaScript, keeps DOM storage and autoplays, so the setters only
 * record their value; [setUserAgentString] is the one that changes the page.
 */
open class WebSettings private constructor(private val onUserAgent: (String?) -> Unit) {
    private var javaScriptEnabled = false
    private var domStorageEnabled = false
    private var mediaPlaybackRequiresUserGesture = true
    private var javaScriptCanOpenWindowsAutomatically = false
    private var supportMultipleWindows = false
    private var userAgent: String? = null

    fun getJavaScriptEnabled() = javaScriptEnabled
    fun setJavaScriptEnabled(flag: Boolean) { javaScriptEnabled = flag }
    fun getDomStorageEnabled() = domStorageEnabled
    fun setDomStorageEnabled(flag: Boolean) { domStorageEnabled = flag }
    fun getMediaPlaybackRequiresUserGesture() = mediaPlaybackRequiresUserGesture
    fun setMediaPlaybackRequiresUserGesture(require: Boolean) { mediaPlaybackRequiresUserGesture = require }
    fun getJavaScriptCanOpenWindowsAutomatically() = javaScriptCanOpenWindowsAutomatically
    fun setJavaScriptCanOpenWindowsAutomatically(flag: Boolean) { javaScriptCanOpenWindowsAutomatically = flag }
    fun supportMultipleWindows() = supportMultipleWindows
    fun setSupportMultipleWindows(support: Boolean) { supportMultipleWindows = support }

    /** Chromium's user agent unless one was set: the one its requests actually carry. */
    fun getUserAgentString(): String = userAgent ?: Chromium.pageUserAgent()

    fun setUserAgentString(ua: String?) {
        userAgent = ua
        onUserAgent(ua)
    }

    internal companion object {
        fun create(onUserAgent: (String?) -> Unit) = WebSettings(onUserAgent)
    }
}
