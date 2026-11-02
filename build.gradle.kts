plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    // 9.5 needs Gradle 9.6+.
    id("com.android.application") version "9.5.0" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
    }
}
