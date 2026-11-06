package desktop.config

import java.io.File

/** Fixed startup configuration from `~/.config/cloudstream-desktop/config.toml`. Settings the UI can change live in [Settings]. */
data class Config(
    val bindHost: String,
    val bindPort: Int,
    val devPath: File,
    val buildOnStart: Boolean,
    val mpvPath: String,
    /** False on a headless host (a container): the server never starts mpv, and playback stays with the clients' own players. */
    val mpvEnabled: Boolean,
    val androidSdk: File?,
    val chromiumPath: String,
    val chromiumIdleMinutes: Long,
    /** OkHttp's per-host limit for the plugins' shared client (the extensions repo's rule is 2). */
    val maxRequestsPerHost: Int,
    /** Defaults for the UI-editable settings, by [Settings] key. */
    val settingDefaults: Map<String, Any>,
) {
    companion object {
        const val DEFAULT_REPO_URL = "https://raw.githubusercontent.com/NropVox/cloudstream-extensions-nropvox/builds/repo.json"

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
            val devPath = File(str("plugins.dev_path") ?: "/mnt/AJ/Projects/cloudstream-extensions-istarvin")

            val defaults = mapOf(
                Settings.MPV_EXTRA_ARGS to ((t["mpv.extra_args"] as? List<*>)?.map { it.toString() } ?: emptyList<String>()),
                Settings.AUTOPLAY_BEST to (bool("playback.autoplay_best") ?: true),
                Settings.AUTOPLAY_NEXT to (bool("playback.autoplay_next") ?: true),
                Settings.SHOW_NSFW to (bool("ui.show_nsfw") ?: false),
                Settings.PROXY_ALL to (bool("playback.proxy_all") ?: false),
                Settings.SKIP_SEGMENTS to (str("playback.skip_segments")?.takeIf { it in Settings.choices.getValue(Settings.SKIP_SEGMENTS) } ?: "prompt"),
                Settings.DISABLED_PLUGINS to ((t["plugins.disabled"] as? List<*>)?.map { it.toString() } ?: listOf("Fetcharr", "StreamCorner")),
                // Without a local extensions repo, plugins come from the published one.
                Settings.PLUGIN_SOURCE to (str("plugins.source")?.takeIf { it in Settings.choices.getValue(Settings.PLUGIN_SOURCE) }
                    ?: if (File(devPath, "settings.gradle.kts").exists()) "dev" else "repo"),
                Settings.PLUGIN_REPO_URLS to ((t["plugins.repo_urls"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() }
                    ?: listOf(str("plugins.repo_url") ?: DEFAULT_REPO_URL)),
                Settings.CHROMIUM_MODE to (str("chromium.mode")?.takeIf { it in Settings.choices.getValue(Settings.CHROMIUM_MODE) } ?: "headless"),
            )
            return Config(
                bindHost = host,
                bindPort = port,
                devPath = devPath,
                buildOnStart = bool("plugins.build_on_start") ?: true,
                mpvPath = str("mpv.path") ?: "mpv",
                mpvEnabled = bool("mpv.enabled") ?: true,
                androidSdk = str("android.sdk_path")?.let(::File),
                chromiumPath = str("chromium.path") ?: "chromium",
                chromiumIdleMinutes = long("chromium.idle_minutes") ?: 10L,
                maxRequestsPerHost = (long("network.max_requests_per_host") ?: 2L).toInt().coerceAtLeast(1),
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
