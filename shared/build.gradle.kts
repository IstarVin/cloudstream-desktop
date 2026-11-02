plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// API shapes and playback rules shared by the server and the Android app. Pure Kotlin: no Cloudstream, Ktor or
// Android classes, so both sides can depend on it.
dependencies {
    api(libs.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
