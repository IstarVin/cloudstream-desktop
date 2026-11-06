package desktop.plugins

import java.io.File

/** Plugin metadata, read from the module's `build.gradle.kts` (`version` and the `cloudstream { }` block). */
data class ModuleInfo(
    val name: String,
    val version: Int?,
    val description: String?,
    val language: String?,
    val tvTypes: List<String>,
    val iconUrl: String?,
    /** 0 down, 1 ok, 2 slow, 3 beta. */
    val status: Int,
    /** Unique among the source's plugins: the name, qualified by the repo in repo mode (see [RepoSource.idOf]). */
    val id: String = name,
    /** The repo URL it comes from (repo mode). */
    val repo: String? = null,
) {
    val nsfw get() = "NSFW" in tvTypes

    companion object {
        fun read(dir: File): ModuleInfo {
            val text = File(dir, "build.gradle.kts").readText()
            fun str(key: String) = Regex("""\b$key\s*=\s*(?:\n\s*)?"((?:[^"\\]|\\.)*)"""").find(text)?.groupValues?.get(1)
            val tvTypes = Regex("""tvTypes\s*=\s*listOf\(([^)]*)\)""").find(text)?.groupValues?.get(1)
                ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
            return ModuleInfo(
                name = dir.name,
                version = Regex("""(?m)^version\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toInt(),
                description = str("description"),
                language = str("language"),
                tvTypes = tvTypes,
                iconUrl = str("iconUrl")?.replace("%size%", "64"),
                status = Regex("""(?m)^\s*status\s*=\s*(\d)""").find(text)?.groupValues?.get(1)?.toInt() ?: 3,
            )
        }
    }
}
