package desktop.plugins

import java.io.File

/** Where plugin jars come from: the local extensions repo (dev mode) or a published repo's `plugins.json`. */
sealed interface PluginSource {
    /** Every plugin this source offers, with the metadata of the version it would load. */
    fun discover(): List<ModuleInfo>

    /** The jar to load for [name], or null when there's none yet. */
    fun jar(name: String): File?

    /** Why [jar] is null, for the plugins page. */
    fun missingReason(name: String): String

    /** A problem with the source as a whole (no extensions repo, repo unreachable), or null. */
    val error: String?
}

/** Dev mode: `<Module>/build/<Module>.jar` in the extensions repo, built by [GradleBuilder] and watched for changes. */
class DevSource(val repo: File) : PluginSource {
    override fun discover(): List<ModuleInfo> =
        repo.listFiles()?.filter { File(it, "build.gradle.kts").exists() }.orEmpty().map(ModuleInfo::read)

    /** Where gradle writes the jar, whether or not it exists yet. */
    fun jarPath(name: String) = File(repo, "$name/build/$name.jar")

    override fun jar(name: String) = jarPath(name).takeIf { it.exists() }

    override fun missingReason(name: String) = "No jar yet: rebuild plugins"

    override val error get() = if (File(repo, "settings.gradle.kts").exists()) null else "No extensions repo at $repo"
}
