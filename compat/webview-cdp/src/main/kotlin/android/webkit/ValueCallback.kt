package android.webkit

/** Receives the JSON-encoded result of [WebView.evaluateJavascript]. */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}
