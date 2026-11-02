import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

// Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
// in the android/ or root dir, with env vars (ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD,
// ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD) taking precedence.
val keystoreProps = Properties().apply {
    listOf(file("keystore.properties"), rootProject.file("keystore.properties"))
        .firstOrNull { it.isFile }
        ?.inputStream()?.use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")

android {
    namespace = "desktop.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cloudstream.desktop.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // AGP 9.3+ DSL: R8 code + resource optimization with the default Android keep rules.
            // App-specific keep rules go in src/main/keepRules/*.keep.
            optimization {
                enable = true
            }
            // Falls back to the debug key when no release keystore is configured.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":shared"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.ass.media)
    implementation(libs.ass.kt)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coroutines.core)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
}
