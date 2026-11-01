package desktop.config

import java.io.File

/** Fixed startup configuration from `~/.config/cloudstream-desktop/config.toml`. Settings the UI can change live in [Settings]. */
data class Config(
    val bindHost: String,
    val bindPort: Int,
    val devPath: File,
    val buildOnStart: Boolean,
    val mpvPath: String,
    val androidSdk: File?,
    /** Defaults for the UI-editable settings, by [Settings] key. */
    val settingDefaults: Map<String, Any>,
) {
    companion object {
        fun load(file: File = Paths.configFile): Config {
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.writeText(javaClass.getResource("/default-config.toml")!!.readText())
            }
            val t = Toml.parse(file.readText())
            val bind = (t["bind"] as? String ?: "0.0.0.0:6969")
            val host = bind.substringBeforeLast(':')
            val port = bind.substringAfterLast(':').toInt()
            fun str(k: String) = t[k] as? String
            fun bool(k: String) = t[k] as? Boolean
            fun long(k: String) = (t[k] as? Number)?.toLong()

            val defaults = mapOf(
                Settings.MPV_EXTRA_ARGS to ((t["mpv.extra_args"] as? List<*>)?.map { it.toString() } ?: emptyList<String>()),
                Settings.AUTOPLAY_BEST to (bool("playback.autoplay_best") ?: true),
                Settings.AUTOPLAY_MIN_LINKS to (long("playback.autoplay_min_links") ?: 3L),
                Settings.AUTOPLAY_WAIT_MS to (long("playback.autoplay_wait_ms") ?: 6000L),
                Settings.AUTOPLAY_NEXT to (bool("playback.autoplay_next") ?: true),
                Settings.SHOW_NSFW to (bool("ui.show_nsfw") ?: false),
                Settings.PROXY_ALL to (bool("playback.proxy_all") ?: false),
                Settings.DISABLED_PLUGINS to ((t["plugins.disabled"] as? List<*>)?.map { it.toString() } ?: listOf("Fetcharr", "StreamCorner")),
            )
            return Config(
                bindHost = host,
                bindPort = port,
                devPath = File(str("plugins.dev_path") ?: "/mnt/AJ/Projects/cloudstream-extensions-istarvin"),
                buildOnStart = bool("plugins.build_on_start") ?: true,
                mpvPath = str("mpv.path") ?: "mpv",
                androidSdk = str("android.sdk_path")?.let(::File),
                settingDefaults = defaults,
            )
        }
    }
}

object Paths {
    private val home = System.getProperty("user.home")
    private fun xdg(env: String, fallback: String) = File(System.getenv(env)?.takeIf { it.isNotBlank() } ?: "$home/$fallback")

    val configDir = File(xdg("XDG_CONFIG_HOME", ".config"), "cloudstream-desktop")
    val configFile = File(configDir, "config.toml")
    val dataDir = File(xdg("XDG_DATA_HOME", ".local/share"), "cloudstream-desktop").apply { mkdirs() }
    val runtimeDir = File(System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir"))
}
