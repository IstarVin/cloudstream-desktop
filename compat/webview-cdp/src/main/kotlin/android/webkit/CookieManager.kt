package android.webkit

import desktop.cdp.Chromium

/**
 * Android's WebView cookie jar, backed by the shared Chromium's. Plugins reach it by reflection to read cookies a
 * WebView solve earned (GoPlay's session cookies). The host classpath shadows android.jar's stub of the same name.
 * Reading or writing starts Chromium if it isn't running; if Chromium is unavailable, there are no cookies.
 */
class CookieManager private constructor() {
    fun getCookie(url: String): String? = runCatching { Chromium.cookiesBlocking(url, start = true) }.getOrNull()
        ?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    fun setCookie(url: String, value: String) {
        runCatching { Chromium.setCookieBlocking(url, value) }
    }

    fun hasCookies(): Boolean = true

    fun acceptCookie(): Boolean = true

    fun setAcceptCookie(@Suppress("UNUSED_PARAMETER") accept: Boolean) {}

    fun flush() {}

    companion object {
        private val instance = CookieManager()

        @JvmStatic
        fun getInstance(): CookieManager = instance
    }
}
