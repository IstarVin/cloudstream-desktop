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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Loads plugin jars from a [PluginSource] (the local extensions repo, or a published repo's jars), one class loader per
 * plugin, the way the app does. `registerMainAPI`/`registerExtractorAPI` fill the library's global lists, which `loadExtractor` reads, so
 * plugins resolve hosts through each other (most need the Extractors plugin).
 */
class PluginHost(private val config: Config, private val settings: Settings) {
    private val log = LoggerFactory.getLogger(PluginHost::class.java)
    private val builder = GradleBuilder(config.devPath)
    private val dev = DevSource(config.devPath)
    private val repoBase = File(Paths.dataDir, "repo")
    private val stubLoader = AndroidStubs.loader(AndroidStubs.findJar(config.androidSdk, config.devPath), javaClass.classLoader)
    private val mutex = Mutex()
    private val cacheDir = File(Paths.dataDir, "plugin-cache").apply { deleteRecursively(); mkdirs() }

    enum class Status { LOADED, ERROR, DISABLED, MISSING }

    class Loaded(
        val loader: URLClassLoader,
        val instance: BasePlugin,
        val context: DesktopContext,
        /** Path and mtime of the jar it came from, to notice a rebuilt or updated jar. */
        val jarKey: String,
        val filename: String,
        /** Labelled settings the plugin declares (see [SettingsSchema]). */
        val settingsSchema: List<SettingField> = emptyList(),
    )

    class Record(@Volatile var info: ModuleInfo) {
        @Volatile var status = Status.MISSING
        @Volatile var error: String? = null
        @Volatile var loaded: Loaded? = null
        @Volatile var lastUsed = 0L
        /** References into the host runtime that won't link (see [LinkageCheck]), found when the plugin loaded. */
        @Volatile var linkage: List<String> = emptyList()
        /** The first linkage error a call into the plugin actually hit. */
        @Volatile var runtimeLinkage: String? = null
        val name get() = info.name
    }

    /** A registered provider and the plugin it came from. */
    class Provider(val api: MainAPI, val plugin: Record) {
        val nsfw get() = plugin.info.nsfw || TvType.NSFW in api.supportedTypes
    }

    private val records = ConcurrentHashMap<String, Record>()

    @Volatile var source: PluginSource = pickSource(null)
        private set

    private val busyCount = AtomicInteger()
    /** Building (dev mode) or downloading (repo mode) plugins. */
    val building get() = busyCount.get() > 0
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

    /** The settings' source; [current] is kept when it already is that source, so its state survives. */
    private fun pickSource(current: PluginSource?): PluginSource = when (settings.string(Settings.PLUGIN_SOURCE)) {
        "repo" -> settings.repoUrls().let { urls ->
            (current as? RepoSource)?.takeIf { it.urls == urls } ?: RepoSource(urls, RepoSource.dirFor(repoBase, urls))
        }
        else -> dev
    }

    /** Adds a record for every plugin the source offers. */
    fun discover() {
        for (info in source.discover()) records.computeIfAbsent(info.name) { Record(info) }
    }

    private suspend fun <T> busy(block: suspend () -> T): T {
        busyCount.incrementAndGet()
        try {
            return block()
        } finally {
            busyCount.decrementAndGet()
        }
    }

    private fun enabled(): List<String> {
        val disabled = settings.disabledPlugins
        return records.keys.filter { it !in disabled }
    }

    suspend fun start(scope: CoroutineScope) {
        discover()
        source.error?.let { log.warn("Plugin source: {}", it) }
        when (val src = source) {
            is DevSource -> if (config.buildOnStart && src.error == null) rebuild()
            // Load what's installed first (that works offline), then look for plugins that are new to the repo.
            is RepoSource -> {
                mutex.withLock { syncAll() }
                fetchRepo(src)
            }
        }
        mutex.withLock { syncAll() }
        logLinkage()
        scope.launch { watch() }
        settings.onChange { key ->
            when (key) {
                Settings.DISABLED_PLUGINS -> scope.launch {
                    (source as? RepoSource)?.let { installEnabled(it) }
                    mutex.withLock { syncAll() }
                }
                Settings.PLUGIN_SOURCE, Settings.PLUGIN_REPO_URLS -> scope.launch { switchSource() }
            }
        }
    }

    /** Unloads everything and loads from the source the settings now name. */
    private suspend fun switchSource() {
        val next = mutex.withLock {
            val next = pickSource(source)
            if (next === source) return
            records.values.forEach(::unload)
            records.clear()
            source = next
            discover()
            syncAll()
            next
        }
        log.info("Plugin source is now {}", if (next is RepoSource) next.urls else config.devPath)
        if (next is RepoSource) {
            fetchRepo(next)
            mutex.withLock { syncAll() }
        }
    }

    /** Refreshes the repo's index (keeping the saved one when that fails) and installs enabled plugins that are new. */
    private suspend fun fetchRepo(repo: RepoSource) = busy {
        runCatching { repo.refresh() }.onFailure { log.warn("Plugin repos {}: {}", repo.urls, it.message) }
        mutex.withLock { discover() }
        installEnabled(repo)
    }

    private suspend fun installEnabled(repo: RepoSource) {
        val missing = enabled().filter { (repo.installed(it) == null || repo.staleConversion(it)) && repo.installable(it) }
        if (missing.isNotEmpty()) busy { repo.install(missing) }.forEach { (name, e) -> log.warn("Couldn't install {}: {}", name, e) }
    }

    private fun repo(): RepoSource = source as? RepoSource
        ?: throw IllegalArgumentException("Updates come from a plugin repo; in dev mode, rebuild the local extensions repo")

    /** Fetches the repo's index again and installs enabled plugins it added. Updates to installed ones wait for [update]. */
    suspend fun checkUpdates() {
        val repo = repo()
        busy { repo.refresh() }
        mutex.withLock { discover() }
        installEnabled(repo)
        mutex.withLock { syncAll() }
    }

    /** Installs the repo's version of [names] (default: every plugin with an update) and reloads them. Returns failures. */
    suspend fun update(names: Collection<String>? = null): Map<String, String> {
        val repo = repo()
        val failed = busy { repo.install(names ?: repo.updates()) }
        mutex.withLock { syncAll() }
        return failed
    }

    suspend fun rollback(name: String) {
        repo().rollback(name)
        mutex.withLock { syncAll() }
    }

    /** Runs gradle for the enabled modules (or [only]); the watcher then reloads whichever jars changed. */
    suspend fun rebuild(only: Collection<String>? = null): GradleBuilder.Result {
        require(source is DevSource) { "Rebuild works on the local extensions repo; switch the plugin source to it first" }
        return busy {
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
            result
        }
    }

    /**
     * Brings every record in line with the settings and the source's jars. Extractors loads first. A record shows the
     * metadata of what's loaded, so it only takes the source's (possibly newer) metadata when it (re)loads or has no jar.
     */
    private fun syncAll() {
        val src = source
        val infos = src.discover().associateBy { it.name }
        val disabled = settings.disabledPlugins
        val order = records.values.sortedBy { if (it.name == "Extractors") "" else it.name }
        for (r in order) {
            val jar = src.jar(r.name)
            when {
                r.name in disabled -> {
                    unload(r)
                    infos[r.name]?.let { r.info = it }
                    r.status = Status.DISABLED
                    r.error = null
                }
                jar == null -> {
                    unload(r)
                    infos[r.name]?.let { r.info = it }
                    // A failed dev build keeps its error; a repo's reason (no jar published, download failed) is fresh.
                    if (r.status != Status.ERROR || src is RepoSource) {
                        r.status = Status.MISSING
                        r.error = src.missingReason(r.name)
                    }
                }
                r.loaded?.jarKey != keyOf(jar) -> {
                    infos[r.name]?.let { r.info = it }
                    load(r, jar)
                }
            }
        }
    }

    private fun keyOf(jar: File) = "${jar.absolutePath}:${jar.lastModified()}"

    private fun load(r: Record, jar: File) {
        unload(r)
        val key = keyOf(jar)
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
            // load() has opened the plugin's preference files by now; a schema entry without a file means the first one.
            val defaultFile = context.openedPrefs.sorted().firstOrNull() ?: r.name
            val schema = SettingsSchema.read(instance.javaClass).map { it.copy(file = it.file ?: defaultFile) }
            r.loaded = Loaded(loader, instance, context, key, copy.absolutePath, schema)
            r.status = Status.LOADED
            r.error = null
            r.runtimeLinkage = null
            r.linkage = runCatching { LinkageCheck.check(copy, loader) }
                .getOrElse { listOf("Linkage check failed: ${it.message}") }
            val apis = APIHolder.allProviders.count { it.sourcePlugin == copy.absolutePath }
            val extractors = extractorApis.count { it.sourcePlugin == copy.absolutePath }
            log.info("Loaded {} v{} ({} providers, {} extractors)", r.name, r.info.version, apis, extractors)
            r.linkage.forEach { log.warn("{} won't link: {}", r.name, it) }
        } catch (t: Throwable) {
            removeApis(copy.absolutePath)
            loader.close()
            r.status = Status.ERROR
            r.error = "${t.javaClass.simpleName}: ${t.message}"
            log.warn("Failed to load {}: {}", r.name, r.error, t)
        }
    }

    /** One line per plugin with linkage problems (or that all loaded plugins link), after startup. */
    private fun logLinkage() {
        val loaded = records.values.filter { it.status == Status.LOADED }
        val broken = loaded.filter { it.linkage.isNotEmpty() }
        if (broken.isEmpty()) log.info("Linkage check: all {} loaded plugins link against this runtime", loaded.size)
        else broken.forEach { log.warn("Linkage check: {} has {} problem(s), first: {}", it.name, it.linkage.size, it.linkage.first()) }
    }

    /**
     * Records a failure from a call into [provider]'s plugin. A [LinkageError] (anywhere in the cause chain) means
     * the plugin and the runtime disagree; the first one is kept and shown on the plugins page.
     */
    fun noteFailure(provider: Provider, t: Throwable) {
        val linkage = generateSequence(t) { it.cause }.take(10).firstOrNull { it is LinkageError } ?: return
        if (provider.plugin.runtimeLinkage == null) {
            provider.plugin.runtimeLinkage = "${linkage.javaClass.simpleName}: ${linkage.message}"
            log.warn("{} hit a linkage error: {}", provider.plugin.name, provider.plugin.runtimeLinkage)
        }
    }

    fun noteFailure(api: MainAPI, t: Throwable) {
        providers().firstOrNull { it.api === api }?.let { noteFailure(it, t) }
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

    /**
     * Dev mode: polls the jars every 2 s and reloads the ones gradle rewrote (`./gradlew :Name:make` or a UI rebuild).
     * Repo mode: looks for plugins that are new to the repo every few hours.
     */
    private suspend fun watch() {
        val mtimes = HashMap<String, Long>()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(2000)
            if (building) continue
            val src = source
            if (src is RepoSource) {
                if (System.currentTimeMillis() - src.checkedAt > REPO_CHECK_MS) {
                    fetchRepo(src)
                    mutex.withLock { syncAll() }
                    // Offline: don't retry every tick.
                    if (src.error != null) delay(REPO_RETRY_MS)
                }
                continue
            }
            mutex.withLock { discover() }
            var changed = false
            for (name in records.keys) {
                val m = dev.jarPath(name).lastModified()
                if (mtimes.put(name, m) != m) changed = true
            }
            // A jar that is still being written is usually smaller than a second old; wait for the next tick.
            val settled = records.keys.none { System.currentTimeMillis() - dev.jarPath(it).lastModified() < 1000 }
            if (changed && settled) mutex.withLock { if (source === src) syncAll() }
        }
    }

    companion object {
        private const val REPO_CHECK_MS = 6 * 3600_000L
        private const val REPO_RETRY_MS = 10 * 60_000L
        private val ANNOTATION = "Lcom/lagradost/cloudstream3/plugins/CloudstreamPlugin;".toByteArray()

        private val MANIFEST_CLASS = Regex(""""pluginClassName"\s*:\s*"([^"]+)"""")

        /**
         * The plugin's entry class: `manifest.json`'s `pluginClassName` when the jar has one (converted `.cs3`s do; dex
         * drops the build-time annotation), else the class carrying `@CloudstreamPlugin` (found by its descriptor in
         * the class file's constant pool).
         */
        fun findEntryClass(jar: File): String? = ZipFile(jar).use { zip ->
            zip.getEntry("manifest.json")?.let { e ->
                MANIFEST_CLASS.find(zip.getInputStream(e).use { it.readBytes().decodeToString() })?.groupValues?.get(1)
            }?.takeIf { zip.getEntry(it.replace('.', '/') + ".class") != null }?.let { return@use it }
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
