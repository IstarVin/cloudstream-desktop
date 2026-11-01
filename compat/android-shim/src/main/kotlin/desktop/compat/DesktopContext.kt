package desktop.compat

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The Context handed to `Plugin.load(context)`. One per plugin, so the server knows which preference files a plugin
 * opened (its settings form edits those). Preference files themselves are shared by name across plugins, as on Android.
 */
class DesktopContext(val pluginName: String) : Context() {
    /** Names of the preference files this plugin opened. */
    val openedPrefs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        openedPrefs.add(name)
        return prefs(name)
    }

    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.lagradost.cloudstream3.desktop"
    override fun getFilesDir(): File = File(dataDir, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(dataDir, "cache").apply { mkdirs() }

    companion object {
        /** Set by the server at startup (`~/.local/share/cloudstream-desktop`). */
        @Volatile
        var dataDir: File = File(System.getProperty("user.home"), ".local/share/cloudstream-desktop")

        /** The app-wide context `CloudStreamApp.context` returns. */
        val app = DesktopContext("app")

        /** File the app's own default preferences live in (`CloudStreamApp.getKey`/`setKey`). */
        const val APP_PREFS = "app"

        private val files = ConcurrentHashMap<String, JsonSharedPreferences>()

        fun prefs(name: String): JsonSharedPreferences =
            files.computeIfAbsent(name) { JsonSharedPreferences(File(File(dataDir, "prefs"), "${safe(it)}.json")) }

        private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
