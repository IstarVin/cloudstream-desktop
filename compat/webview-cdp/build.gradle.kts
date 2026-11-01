// Desktop stand-ins for the app's WebView-backed network classes (tier C). In v1, CloudflareKiller lets requests
// through and fails with a clear error when a site actually serves a challenge. v1.1 backs it with Chromium over CDP.
plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(libs.cloudstream.library)
    compileOnly(libs.nicehttp)
}
