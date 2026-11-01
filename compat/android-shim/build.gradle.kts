// The Android and Cloudstream-app classes that tier B plugins call at runtime: a working Context,
// JSON-backed SharedPreferences, Log, Base64, Uri, Dispatchers.Main, plugins.Plugin, CloudStreamApp/AcraApplication
// and DataStore.
// Everything else Android (views, dialogs, fragments) only has to exist for the bytecode verifier; the server
// puts the SDK's android.jar stubs behind these classes for that.
plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(libs.cloudstream.library)
    compileOnly(libs.jackson.kotlin)
    compileOnly(libs.jackson.databind)
    compileOnly(libs.coroutines.core)
}
