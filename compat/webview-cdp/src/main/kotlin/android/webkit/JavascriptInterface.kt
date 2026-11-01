package android.webkit

/** Marks the methods of an object passed to [WebView.addJavascriptInterface] that pages may call. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JavascriptInterface
