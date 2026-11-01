// Desktop implementations of the app's WebView-backed network classes (tier C), backed by Chromium over CDP:
// WebViewResolver (same name and signatures as the library's, which is a TODO() on the JVM), CloudflareKiller and
// android.webkit.CookieManager. The server puts this jar ahead of library-jvm on its classpath.
plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(libs.cloudstream.library)
    compileOnly(libs.nicehttp)
    compileOnly(libs.coroutines.core)
    compileOnly(libs.serialization.json)
}
