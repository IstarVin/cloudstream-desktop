package desktop.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Repo mode: plugins from a published Cloudstream repo. [url] is a `repo.json` (its `pluginLists`) or a `plugins.json`.
 * The gradle plugin lists a `jarUrl`/`jarHash`/`jarFileSize` for every `isCrossPlatform` module; those jars are used as
 * they are. Any other plugin's `.cs3` (`url`/`fileHash`/`fileSize`) is converted to a jar with [Cs3Converter]. Every
 * download is checked against the repo's hash, and the previous two versions are kept in [dir] for rollback. The index
 * and what's installed are saved, so the plugins load offline.
 */
class RepoSource(val url: String, private val dir: File, private val http: OkHttpClient = defaultClient) : PluginSource {
    private val log = LoggerFactory.getLogger(RepoSource::class.java)
    private val stateFile = File(dir, "state.json")
    private val jarDir = File(dir, "jars")
    private val mutex = Mutex()
    private val installErrors = ConcurrentHashMap<String, String>()

    @Volatile private var state: State = load()
    @Volatile private var fetchError: String? = null

    /** One plugin as `plugins.json` lists it (only the fields the desktop uses). */
    @Serializable
    data class Entry(
        val internalName: String,
        val version: Int,
        val description: String? = null,
        val language: String? = null,
        val tvTypes: List<String>? = null,
        val iconUrl: String? = null,
        val status: Int = 3,
        /** The `.cs3`. */
        val url: String? = null,
        val fileHash: String? = null,
        val fileSize: Long? = null,
        val jarUrl: String? = null,
        val jarHash: String? = null,
        val jarFileSize: Long? = null,
    ) {
        fun info() = ModuleInfo(
            name = internalName,
            version = version,
            description = description,
            language = language,
            tvTypes = tvTypes.orEmpty(),
            iconUrl = iconUrl?.replace("%size%", "64"),
            status = status,
        )
    }

    @Serializable
    data class Jar(
        val entry: Entry,
        val file: String,
        val installedAt: Long,
        /** Converted from the `.cs3` because the repo published no jar. */
        val converted: Boolean = false,
        /** The [Cs3Converter.VERSION] that converted it. */
        val converter: Int = 0,
    )

    @Serializable
    data class Installed(val current: Jar, val previous: List<Jar> = emptyList())

    @Serializable
    private data class State(
        val repoName: String? = null,
        val index: List<Entry> = emptyList(),
        val checkedAt: Long = 0,
        val installed: Map<String, Installed> = emptyMap(),
    )

    val repoName get() = state.repoName
    val checkedAt get() = state.checkedAt

    fun available(name: String): Entry? = state.index.firstOrNull { it.internalName == name }
    fun installed(name: String): Installed? = state.installed[name]

    /** [name]'s jar was converted by an older [Cs3Converter] from the version the repo still offers: convert it again. */
    fun staleConversion(name: String): Boolean {
        val current = installed(name)?.current ?: return false
        return current.converted && current.converter < Cs3Converter.VERSION && available(name)?.version == current.entry.version
    }

    /** The repo has something to install for [name]: a jar, or a `.cs3` to convert. */
    fun installable(name: String): Boolean = available(name)?.let { it.jarUrl != null || it.url != null } == true

    /** Installed plugins whose repo version is newer. */
    fun updates(): List<String> = state.installed.filter { (name, i) ->
        installable(name) && available(name)!!.version > i.current.entry.version
    }.keys.sorted()

    override val error get() = fetchError?.let { if (state.checkedAt == 0L) "Couldn't reach the plugin repo: $it" else "Couldn't check for updates: $it" }

    override fun discover(): List<ModuleInfo> {
        val s = state
        val names = s.index.map { it.internalName } + s.installed.keys
        return names.distinct().map { name -> (s.installed[name]?.current?.entry ?: s.index.first { it.internalName == name }).info() }
    }

    override fun jar(name: String): File? = state.installed[name]?.current?.file?.let(::File)?.takeIf { it.exists() }

    override fun missingReason(name: String): String {
        installErrors[name]?.let { return it }
        val entry = available(name)
        return when {
            entry == null && fetchError != null -> error!!
            entry == null || !installable(name) -> "The repo publishes neither a jar nor a .cs3 for this plugin"
            else -> "Not downloaded yet"
        }
    }

    /** Fetches the index again. Throws when the repo can't be read; the saved index stays in use. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val root = Json.parseToJsonElement(fetchText(url))
            val (name, lists) = if (root is JsonObject && "pluginLists" in root) {
                root["name"]?.jsonPrimitive?.content to root.getValue("pluginLists").jsonArray.map { resolve(url, it.jsonPrimitive.content) }
            } else {
                null to listOf(url)
            }
            val index = lists.flatMap { list -> entries(if (list == url) root else Json.parseToJsonElement(fetchText(list)), list) }
            mutex.withLock { save(state.copy(repoName = name, index = index, checkedAt = System.currentTimeMillis())) }
            fetchError = null
            log.info("Plugin repo {}: {} plugins, {} with jars (the rest are converted from their .cs3)", name ?: url, index.size, index.count { it.jarUrl != null })
        } catch (e: Exception) {
            fetchError = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    private fun entries(json: JsonElement, base: String): List<Entry> = (json as? JsonArray ?: throw IOException("$base isn't a plugin list"))
        .map { lenient.decodeFromJsonElement<Entry>(it) }
        .map { e -> e.copy(jarUrl = e.jarUrl?.let { resolve(base, it) }, url = e.url?.let { resolve(base, it) }) }

    /** Downloads the repo's version of each of [names] that isn't installed or is older. Returns the failures. */
    suspend fun install(names: Collection<String>): Map<String, String> = coroutineScope {
        val gate = Semaphore(4)
        names.map { name -> async { gate.withPermit { name to runCatching { install(name) }.exceptionOrNull() } } }.awaitAll()
            .mapNotNull { (name, e) -> e?.let { name to (it.message ?: it.javaClass.simpleName) } }.toMap()
    }

    /** Installs the repo's version of [name]; a no-op when that version is already the current one. */
    suspend fun install(name: String): Unit = withContext(Dispatchers.IO) {
        val entry = available(name) ?: throw IOException("$name isn't in the repo")
        if (!installable(name)) throw IOException(missingReason(name))
        val current = installed(name)?.current
        val same = current != null && current.entry.version == entry.version &&
            current.entry.jarHash == entry.jarHash && current.entry.fileHash == entry.fileHash
        if (same && File(current.file).exists() && !staleConversion(name)) return@withContext
        try {
            val converted = entry.jarUrl == null
            val file = if (converted) convert(name, entry) else download(name, entry.jarUrl, entry.jarHash, entry.jarFileSize, "jar")
            mutex.withLock {
                val old = state.installed[name]
                val previous = listOfNotNull(old?.current) + old?.previous.orEmpty()
                val keep = previous.filter { it.entry.version != entry.version && it.file != file.path }.take(KEEP_PREVIOUS)
                (previous - keep.toSet()).forEach { if (it.file != file.path) File(it.file).delete() }
                val jar = Jar(entry, file.path, System.currentTimeMillis(), converted, if (converted) Cs3Converter.VERSION else 0)
                save(state.copy(installed = state.installed + (name to Installed(jar, keep))))
            }
            installErrors.remove(name)
            log.info("Installed {} v{} from the repo{}", name, entry.version, if (converted) " (converted from its .cs3)" else "")
        } catch (e: Exception) {
            installErrors[name] = "Install failed: ${e.message ?: e.javaClass.simpleName}"
            throw e
        }
    }

    /** Makes the newest previous version current again. The rolled-back jar is dropped; Update fetches it again. */
    suspend fun rollback(name: String) = mutex.withLock {
        val old = state.installed[name] ?: throw IllegalArgumentException("$name isn't installed")
        val back = old.previous.firstOrNull { File(it.file).exists() } ?: throw IllegalArgumentException("$name has no earlier version to roll back to")
        File(old.current.file).delete()
        save(state.copy(installed = state.installed + (name to Installed(back, old.previous.drop(old.previous.indexOf(back) + 1)))))
        log.info("Rolled {} back to v{}", name, back.entry.version)
    }

    /** Downloads the `.cs3` and converts it; only the jar is kept. */
    private fun convert(name: String, entry: Entry): File {
        val cs3 = download(name, entry.url!!, entry.fileHash, entry.fileSize, "cs3")
        try {
            val jar = File(cs3.path.removeSuffix(".cs3") + "-converted.jar")
            Cs3Converter.convert(cs3, jar)
            return jar
        } finally {
            cs3.delete()
        }
    }

    /** Downloads [url] into the jar folder, checking its size and `sha256-` [hash]. */
    private fun download(name: String, url: String, hash: String?, expectedSize: Long?, ext: String): File {
        val expected = hash?.removePrefix("sha256-")?.lowercase() ?: throw IOException("The repo lists no hash for $name's $ext")
        jarDir.mkdirs()
        val target = File(jarDir, "$name-v${available(name)?.version}-${expected.take(12)}.$ext")
        val tmp = File(jarDir, "${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            fetchInto(tmp, digest, name, url, expectedSize, ext)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException("$name's $ext doesn't match the repo's hash (got sha256-$actual)")
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally {
            tmp.delete()
        }
        return target
    }

    private fun fetchInto(tmp: File, digest: MessageDigest, name: String, url: String, expectedSize: Long?, ext: String) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $url")
            var size = 0L
            res.body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_JAR_BYTES) throw IOException("$name's $ext is larger than ${MAX_JAR_BYTES / 1_000_000} MB")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            expectedSize?.let { if (size != it) throw IOException("$name's $ext is $size bytes; the repo says $it") }
        }
    }

    private fun fetchText(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use { res ->
        if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $url")
        res.body.string()
    }

    private fun load(): State = runCatching { lenient.decodeFromString<State>(stateFile.readText()) }.getOrElse { State() }

    private fun save(s: State) {
        state = s
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(lenient.encodeToString(State.serializer(), s))
        if (!tmp.renameTo(stateFile)) tmp.copyTo(stateFile, overwrite = true)
    }

    companion object {
        const val KEEP_PREVIOUS = 2
        private const val MAX_JAR_BYTES = 50_000_000L
        private val lenient = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Each repo URL keeps its own downloads, so switching repos never mixes their jars. */
        fun dirFor(base: File, url: String): File {
            val id = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
            return File(base, id)
        }

        private fun resolve(base: String, ref: String): String = base.toHttpUrl().resolve(ref)?.toString() ?: ref
    }
}
