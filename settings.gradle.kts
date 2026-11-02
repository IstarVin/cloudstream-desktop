rootProject.name = "cloudstream-desktop"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

include(":shared", ":compat:android-shim", ":compat:webview-cdp", ":server")

// The Android app needs an Android SDK; without one (CI's server job, a desktop-only checkout) it's left out so the
// server still builds.
val androidSdk = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).firstOrNull()
    ?: file("local.properties").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
if (androidSdk != null && file(androidSdk).isDirectory) include(":android")
