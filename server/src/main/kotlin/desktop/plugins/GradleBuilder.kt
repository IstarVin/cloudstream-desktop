package desktop.plugins

import desktop.config.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds plugin jars in the extensions repo with `gradlew -I desktop-jars.init.gradle :Name:compilePluginJar`.
 * The init script makes every module emit a JVM jar without touching the repo's build files.
 */
class GradleBuilder(private val repo: File) {
    private val log = LoggerFactory.getLogger(GradleBuilder::class.java)

    data class Result(val ok: Boolean, val output: String, val failed: Set<String>)

    private val initScript: File by lazy {
        File(Paths.dataDir, "desktop-jars.init.gradle").apply {
            writeText(GradleBuilder::class.java.getResource("/gradle/desktop-jars.init.gradle")!!.readText())
        }
    }

    suspend fun build(modules: Collection<String>): Result = withContext(Dispatchers.IO) {
        if (modules.isEmpty()) return@withContext Result(true, "", emptySet())
        val cmd = listOf("./gradlew", "--console=plain", "--continue", "-I", initScript.absolutePath) +
            modules.map { ":$it:compilePluginJar" }
        log.info("Building {} plugin(s) in {}", modules.size, repo)
        val logFile = File(Paths.dataDir, "gradle-build.log")
        val proc = ProcessBuilder(cmd).directory(repo).redirectErrorStream(true)
            .redirectOutput(logFile).start()
        val finished = proc.waitFor(20, TimeUnit.MINUTES)
        if (!finished) proc.destroyForcibly()
        val output = logFile.readText()
        // With --continue, gradle names each failed task as "Execution failed for task ':Name:...'".
        val failed = Regex("""Execution failed for task ':([^:]+):""").findAll(output).map { it.groupValues[1] }.toSet()
        val ok = finished && proc.exitValue() == 0
        if (!ok) log.warn("Plugin build failed for {} (see {})", failed.ifEmpty { "some modules" }, logFile)
        Result(ok, output, failed)
    }
}
