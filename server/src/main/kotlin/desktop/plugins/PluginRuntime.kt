package desktop.plugins

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import desktop.cdp.Chromium
import desktop.config.Config
import desktop.config.Paths
import desktop.config.Settings
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Process-wide setup of what plugins run on: the shared HTTP client's limits, the sync-id keys and the Chromium
 * behind tier C.
 */
object PluginRuntime {
    private val log = LoggerFactory.getLogger(PluginRuntime::class.java)

    fun configure(config: Config, settings: Settings) {
        checkWebViewResolver()

        limitRequestsPerHost(config.maxRequestsPerHost)
        setSyncIdPrefixes()

        Chromium.configure(
            Chromium.Options(
                path = config.chromiumPath,
                mode = Chromium.Mode.parse(settings.string(Settings.CHROMIUM_MODE)),
                dataDir = File(Paths.dataDir, "chromium"),
                idleMs = config.chromiumIdleMinutes * 60_000,
            ),
        )
        settings.onChange { key ->
            if (key == Settings.CHROMIUM_MODE) {
                Chromium.setMode(Chromium.Mode.parse(settings.string(key)))
                // The next request that needs a browser starts it in the new mode.
                Chromium.stop()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { Chromium.stop() })
    }

    /**
     * Every client NiceHttp builds for a request (`newBuilder()` per interceptor or timeout) shares the base client's
     * dispatcher, so this caps parallel requests per host across all plugins. Waiting calls queue in OkHttp instead
     * of holding threads.
     */
    fun limitRequestsPerHost(max: Int) {
        app.baseClient.dispatcher.maxRequestsPerHost = max
    }

    /**
     * `addMalId`, `addAniListId` and friends store ids in a load response's `syncData` under these keys. The app sets
     * them from its sync services at startup; on the JVM they're all "", so every id would overwrite the others.
     * Same values as the app's (MALApi, AniListApi, KitsuApi, SimklApi `idPrefix`).
     */
    fun setSyncIdPrefixes() {
        LoadResponse.malIdPrefix = "MAL"
        LoadResponse.aniListIdPrefix = "AniList"
        LoadResponse.kitsuIdPrefix = "Kitsu"
        LoadResponse.simklIdPrefix = "SIMKL"
    }

    /**
     * The library ships a WebViewResolver of the same name whose resolve is a `TODO()`. The compat jar's must come
     * first on the classpath; if it doesn't, every tier C plugin would fail with `NotImplementedError`.
     */
    private fun checkWebViewResolver() {
        val cls = WebViewResolver::class.java
        val desktop = cls.declaredFields.any { it.name == "DESKTOP_IMPLEMENTATION" }
        val from = cls.protectionDomain?.codeSource?.location
        if (!desktop) error("WebViewResolver was loaded from $from (the library's), not compat/webview-cdp: fix the classpath order")
        log.debug("WebViewResolver from {}", from)
    }
}
