package desktop.plugins

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.Properties

/**
 * A class-loader layer over the Android SDK's `android.jar`, between the host and the plugins.
 *
 * Plugins that subclass `Plugin` build their settings dialogs in lambdas whose bodies live in the plugin class, so the
 * bytecode verifier loads `Activity`, `EditText` and friends while linking it, even though the dialog never runs here.
 * android.jar supplies those shapes (its methods just throw). Delegation is parent-first, so the working shims on the
 * host classpath (`Context`, `SharedPreferences`, `Log`, `Base64`) win over the stubs of the same name.
 */
object AndroidStubs {
    private val log = LoggerFactory.getLogger(AndroidStubs::class.java)

    fun findJar(configured: File?, devPath: File): File? {
        val candidates = buildList {
            configured?.let(::add)
            File(devPath, "local.properties").takeIf { it.exists() }?.let { f ->
                Properties().apply { f.reader().use(::load) }.getProperty("sdk.dir")?.let { add(File(it)) }
            }
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull(System::getenv).forEach { add(File(it)) }
            add(File(System.getProperty("user.home"), "Android/Sdk"))
        }
        for (sdk in candidates) {
            val jar = File(sdk, "platforms").listFiles()
                ?.filter { File(it, "android.jar").exists() }
                ?.maxByOrNull { it.name.removePrefix("android-").toDoubleOrNull() ?: 0.0 }
                ?.let { File(it, "android.jar") }
            if (jar != null) return jar
        }
        return null
    }

    fun loader(jar: File?, parent: ClassLoader): ClassLoader {
        if (jar == null) {
            log.warn("No Android SDK found: plugins with Android settings dialogs may fail to load. Set android.sdk_path in config.toml.")
            return parent
        }
        log.info("Android class stubs from {}", jar)
        return URLClassLoader("android-stubs", arrayOf(jar.toURI().toURL()), parent)
    }
}
