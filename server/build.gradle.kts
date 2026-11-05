plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":compat:android-shim"))
    implementation(project(":compat:webview-cdp"))
    implementation(libs.cloudstream.library)

    // The plugin runtime. Plugin jars don't bundle their `implementation` dependencies, so the host provides the
    // versions the extensions repo builds against.
    implementation(libs.nicehttp)
    implementation(libs.jsoup)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.databind)
    implementation(libs.rhino)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.fuzzywuzzy)
    implementation(libs.gson)
    implementation(libs.bcpkix)
    implementation(libs.androidx.annotation)

    // Repo mode: converts a plugin's .cs3 (Android dex) into a JVM jar when the repo publishes no jar for it.
    implementation(libs.dex2jar)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback)
    implementation(libs.jul.to.slf4j)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("desktop.MainKt")
    // Without a cap the JVM may grow to a quarter of system RAM and keep it; the periodic GC hands idle heap back.
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx1g", "-XX:G1PeriodicGCInterval=30000", "-XX:MaxHeapFreeRatio=30", "-XX:MinHeapFreeRatio=10",
    )
}

kotlin {
    compilerOptions {
        optIn.add("com.lagradost.cloudstream3.Prerelease")
        optIn.add("com.lagradost.cloudstream3.InternalAPI")
    }
}

// The web UI: `vp build` writes the static SPA into src/main/resources/web, which Ktor serves at `/`.
val webDir = rootProject.file("web")
val webInstall = tasks.register<Exec>("webInstall") {
    workingDir = webDir
    commandLine("vp", "install")
    inputs.files(webDir.resolve("package.json"), webDir.resolve("pnpm-lock.yaml"))
    outputs.dir(webDir.resolve("node_modules"))
}
val webBuild = tasks.register<Exec>("webBuild") {
    dependsOn(webInstall)
    workingDir = webDir
    commandLine("vp", "build")
    if (environment.containsKey("NO_COLOR")) {
        // Vite+ sets FORCE_COLOR for its child processes; preserve no-color output without conflicting variables.
        environment.remove("NO_COLOR")
        environment("FORCE_COLOR", "0")
    }
    inputs.dir(webDir.resolve("src"))
    inputs.files(webDir.resolve("package.json"), webDir.resolve("vite.config.ts"))
    outputs.dir(layout.projectDirectory.dir("src/main/resources/web"))
}

// The mpv script ships inside the server jar (extracted at runtime when not running from a checkout).
tasks.processResources {
    dependsOn(webBuild)
    from(rootProject.file("mpv")) { into("mpv") }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("cloudstream-desktop.jar")
    // Ktor, sqlite-jdbc and kotlinx register implementations through META-INF/services.
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

tasks.named<JavaExec>("run") {
    // `./gradlew :server:run --args="spike Dulo dune"` passes CLI args; the working dir is the repo root so the
    // mpv script path resolves without an install.
    workingDir = rootDir
    standardInput = System.`in`
}
