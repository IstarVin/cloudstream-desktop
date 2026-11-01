package desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import desktop.compat.DesktopContext
import desktop.config.Config
import desktop.config.Paths
import desktop.config.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Loads plugin jars from the extensions repo (`<Module>/build/<Module>.jar`), one class loader per plugin, the way the
 * app does. `registerMainAPI`/`registerExtractorAPI` fill the library's global lists, which `loadExtractor` reads, so
 * plugins resolve hosts through each other (most need the Extractors plugin).
 */
class PluginHost(private val config: Config, private val settings: Settings) {
    private val log = LoggerFactory.getLogger(PluginHost::class.java)
    private val builder = GradleBuilder(config.devPath)
    private val stubLoader = AndroidStubs.loader(AndroidStubs.findJar(config.androidSdk, config.devPath), javaClass.classLoader)
    private val mutex = Mutex()
    private val cacheDir = File(Paths.dataDir, "plugin-cache").apply { deleteRecursively(); mkdirs() }

    enum class Status { LOADED, ERROR, DISABLED, MISSING }

    class Loaded(
        val loader: URLClassLoader,
        val instance: BasePlugin,
        val context: DesktopContext,
        val jarMtime: Long,
        val filename: String,
    )

    class Record(val info: ModuleInfo) {
        @Volatile var status = Status.MISSING
        @Volatile var error: String? = null
        @Volatile var loaded: Loaded? = null
        @Volatile var lastUsed = 0L
        val name get() = info.name
    }

    /** A registered provider and the plugin it came from. */
    class Provider(val api: MainAPI, val plugin: Record) {
        val nsfw get() = plugin.info.nsfw || TvType.NSFW in api.supportedTypes
    }

    private val records = ConcurrentHashMap<String, Record>()

    @Volatile var building = false
        private set
    @Volatile var lastBuildOutput: String = ""
        private set

    val plugins: List<Record> get() = records.values.sortedBy { it.name.lowercase() }

    fun providers(): List<Provider> {
        val byFile = records.values.mapNotNull { r -> r.loaded?.let { it.filename to r } }.toMap()
        return APIHolder.allProviders.toList().mapNotNull { api -> byFile[api.sourcePlugin]?.let { Provider(api, it) } }
    }

    fun provider(name: String): Provider? = providers().firstOrNull { it.api.name == name }?.also {
        it.plugin.lastUsed = System.currentTimeMillis()
    }

    private fun jarOf(name: String) = File(config.devPath, "$name/build/$name.jar")

    /** Module folders in the extensions repo (every top-level folder with a build.gradle.kts, as its settings do). */
    fun discover() {
        val dirs = config.devPath.listFiles()?.filter { File(it, "build.gradle.kts").exists() }.orEmpty()
        for (dir in dirs) records.computeIfAbsent(dir.name) { Record(ModuleInfo.read(dir)) }
    }

    private fun enabled(): List<String> {
        val disabled = settings.disabledPlugins
        return records.keys.filter { it !in disabled }
    }

    suspend fun start(scope: CoroutineScope) {
        discover()
        if (config.buildOnStart) rebuild()
        mutex.withLock { syncAll() }
        scope.launch { watch() }
        settings.onChange { key -> if (key == Settings.DISABLED_PLUGINS) scope.launch { mutex.withLock { syncAll() } } }
    }

    /** Runs gradle for the enabled modules (or [only]); the watcher then reloads whichever jars changed. */
    suspend fun rebuild(only: Collection<String>? = null): GradleBuilder.Result {
        building = true
        try {
            val result = builder.build(only ?: enabled())
            lastBuildOutput = result.output.takeLast(20_000)
            mutex.withLock {
                for (name in result.failed) records[name]?.let { r ->
                    if (r.loaded == null) {
                        r.status = Status.ERROR
                        r.error = "Gradle build failed; see Settings → Plugins → build log"
                    }
                }
                syncAll()
            }
            return result
        } finally {
            building = false
        }
    }

    /** Brings every record in line with the settings and the jars on disk. Extractors loads first. */
    private fun syncAll() {
        val disabled = settings.disabledPlugins
        val order = records.values.sortedBy { if (it.name == "Extractors") "" else it.name }
        for (r in order) {
            val jar = jarOf(r.name)
            when {
                r.name in disabled -> {
                    unload(r)
                    r.status = Status.DISABLED
                    r.error = null
                }
                !jar.exists() -> {
                    unload(r)
                    if (r.status != Status.ERROR) {
                        r.status = Status.MISSING
                        r.error = "No jar yet: rebuild plugins"
                    }
                }
                r.loaded?.jarMtime != jar.lastModified() -> load(r, jar)
            }
        }
    }

    private fun load(r: Record, jar: File) {
        unload(r)
        val mtime = jar.lastModified()
        // Load a private copy: gradle may rewrite the jar while the old class loader still has it open.
        val copy = File(cacheDir, "${r.name}-$mtime.jar")
        jar.copyTo(copy, overwrite = true)
        val loader = URLClassLoader("plugin-${r.name}", arrayOf(copy.toURI().toURL()), stubLoader)
        try {
            val entry = findEntryClass(copy) ?: error("No @CloudstreamPlugin class in ${jar.name}")
            val instance = loader.loadClass(entry).getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = copy.absolutePath
            val context = DesktopContext(r.name)
            if (instance is Plugin) instance.load(context) else instance.load()
            r.loaded = Loaded(loader, instance, context, mtime, copy.absolutePath)
            r.status = Status.LOADED
            r.error = null
            val apis = APIHolder.allProviders.count { it.sourcePlugin == copy.absolutePath }
            val extractors = extractorApis.count { it.sourcePlugin == copy.absolutePath }
            log.info("Loaded {} v{} ({} providers, {} extractors)", r.name, r.info.version, apis, extractors)
        } catch (t: Throwable) {
            removeApis(copy.absolutePath)
            loader.close()
            r.status = Status.ERROR
            r.error = "${t.javaClass.simpleName}: ${t.message}"
            log.warn("Failed to load {}: {}", r.name, r.error, t)
        }
    }

    private fun unload(r: Record) {
        val loaded = r.loaded ?: return
        r.loaded = null
        runCatching { loaded.instance.beforeUnload() }
        removeApis(loaded.filename)
        loaded.loader.close()
        File(loaded.filename).delete()
        log.info("Unloaded {}", r.name)
    }

    private fun removeApis(filename: String) {
        APIHolder.allProviders.filter { it.sourcePlugin == filename }.forEach {
            APIHolder.allProviders.remove(it)
            APIHolder.removePluginMapping(it)
        }
        extractorApis.removeAll { it.sourcePlugin == filename }
    }

    /** Polls the jars every 2 s and reloads the ones gradle rewrote (`./gradlew :Name:make` or a UI rebuild). */
    private suspend fun watch() {
        val mtimes = HashMap<String, Long>()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(2000)
            if (building) continue
            discover()
            var changed = false
            for (name in records.keys) {
                val m = jarOf(name).lastModified()
                if (mtimes.put(name, m) != m) changed = true
            }
            // A jar that is still being written is usually smaller than a second old; wait for the next tick.
            val settled = records.keys.none { System.currentTimeMillis() - jarOf(it).lastModified() < 1000 }
            if (changed && settled) mutex.withLock { syncAll() }
        }
    }

    companion object {
        private val ANNOTATION = "Lcom/lagradost/cloudstream3/plugins/CloudstreamPlugin;".toByteArray()

        /** The class carrying `@CloudstreamPlugin` (found by its descriptor in the class file's constant pool). */
        fun findEntryClass(jar: File): String? = ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.firstOrNull { e ->
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                indexOf(bytes, ANNOTATION) >= 0
            }?.name?.removeSuffix(".class")?.replace('/', '.')
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
