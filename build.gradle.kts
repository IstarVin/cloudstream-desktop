plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    // 9.3+ needs a newer Gradle than the wrapper's.
    id("com.android.application") version "9.2.1" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
    }
}
