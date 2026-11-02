// Plugin versions live in gradle/libs.versions.toml; they're loaded once here so the modules apply them by id.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.shadow) apply false
}

val jvmVersion = libs.versions.jvm.get().toInt()

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(jvmVersion)
        }
    }
}
