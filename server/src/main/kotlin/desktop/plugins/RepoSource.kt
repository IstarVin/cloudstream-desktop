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
import kotlinx.serialization.json.contentOrNull
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
 * Repo mode: plugins from published Cloudstream repos. Each of [urls] is a `repo.json` (its `pluginLists`) or a `plugins.json`.
 * Plugins are kept per repo: a plugin's id is its name plus its repo's [repoKey], so two repos may both publish a plugin
 * with the same name and both can be installed and loaded. A bare plugin name still resolves (see [idFor]) to the copy
 * from the earliest repo, which is how settings and API calls made before plugins had ids keep working.
 * The gradle plugin lists a `jarUrl`/`jarHash`/`jarFileSize` for every `isCrossPlatform` module; those jars are used as
 * they are. Any other plugin's `.cs3` (`url`/`fileHash`/`fileSize`) is converted to a jar with [Cs3Converter]. Every
 * download is checked against the repo's hash, and the previous two versions are kept in [dir] for rollback. The index
 * and what's installed are saved, so the plugins load offline. Removing a repo from [urls] deletes what it installed.
 */
class RepoSource(val urls: List<String>, private val dir: File, private val http: OkHttpClient = defaultClient) : PluginSource {
    constructor(url: String, dir: File, http: OkHttpClient = defaultClient) : this(listOf(url), dir, http)

    private val log = LoggerFactory.getLogger(RepoSource::class.java)
    private val stateFile = File(dir, "state.json")
    private val jarDir = File(dir, "jars")
    private val mutex = Mutex()
    private val installErrors = ConcurrentHashMap<String, String>()
    private val fetchErrors = ConcurrentHashMap<String, String>()

    @Volatile private var state: State = load()

    init {
        dropRemovedRepos()
    }

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
        /** The repo URL this entry came from. */
        val repo: String? = null,
    ) {
        val id get() = idOf(internalName, repo)

        fun info() = ModuleInfo(
            name = internalName,
            version = version,
            description = description,
            language = language,
            tvTypes = tvTypes.orEmpty(),
            iconUrl = iconUrl?.replace("%size%", "64"),
            status = status,
            id = id,
            repo = repo,
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
        /** Each repo's `name` from its `repo.json`, by URL. */
        val repoNames: Map<String, String> = emptyMap(),
        /** Repository icon URLs, retained for offline use. */
        val repoIcons: Map<String, String> = emptyMap(),
        val index: List<Entry> = emptyList(),
        val checkedAt: Long = 0,
        /** By plugin id. */
        val installed: Map<String, Installed> = emptyMap(),
    )

    /** A configured repo, for the UI. */
    data class Repo(val url: String, val key: String, val name: String?, val error: String?, val iconUrl: String?)

    fun repos(): List<Repo> = urls.map { Repo(it, repoKey(it), state.repoNames[it], fetchErrors[it], state.repoIcons[it]) }

    /** The repos' names, joined, or null before any was read. */
    val repoName get() = urls.mapNotNull { state.repoNames[it] }.joinToString(", ").ifEmpty { null }
    val checkedAt get() = state.checkedAt

    /**
     * The id [ref] names: an id as it is, or a bare plugin name as the copy from the earliest repo that has it
     * (installed copies first), or null.
     */
    fun idFor(ref: String): String? {
        val s = state
        if (ref in s.installed || s.index.any { it.id == ref }) return ref
        val rank = { e: Entry -> urls.indexOf(e.repo).let { if (it < 0) Int.MAX_VALUE else it } }
        return (s.installed.values.map { it.current.entry }.filter { it.internalName == ref }.minByOrNull(rank)
            ?: s.index.filter { it.internalName == ref }.minByOrNull(rank))?.id
    }

    fun available(ref: String): Entry? = idFor(ref)?.let { id -> state.index.firstOrNull { it.id == id } }
    fun installed(ref: String): Installed? = idFor(ref)?.let { state.installed[it] }

    /** [ref]'s jar was converted by an older [Cs3Converter] from the version the repo still offers: convert it again. */
    fun staleConversion(ref: String): Boolean {
        val current = installed(ref)?.current ?: return false
        return current.converted && current.converter < Cs3Converter.VERSION && available(ref)?.version == current.entry.version
    }

    /** The repo has something to install for [ref]: a jar, or a `.cs3` to convert. */
    fun installable(ref: String): Boolean = available(ref)?.let { it.jarUrl != null || it.url != null } == true

    /** Ids of installed plugins whose repo version is newer. */
    fun updates(): List<String> = state.installed.filter { (id, i) ->
        installable(id) && available(id)!!.version > i.current.entry.version
    }.keys.sorted()

    override val error: String?
        get() {
            if (fetchErrors.isEmpty()) return null
            val detail = urls.mapNotNull { u -> fetchErrors[u]?.let { "$u: $it" } }.joinToString("; ")
            return if (state.checkedAt == 0L) "Couldn't reach the plugin repo: $detail" else "Couldn't check for updates: $detail"
        }

    override fun discover(): List<ModuleInfo> {
        val s = state
        val ids = s.index.map { it.id } + s.installed.keys
        return ids.distinct().map { id -> (s.installed[id]?.current?.entry ?: s.index.first { it.id == id }).info() }
    }

    override fun jar(id: String): File? = installed(id)?.current?.file?.let(::File)?.takeIf { it.exists() }

    override fun missingReason(id: String): String {
        @Suppress("NAME_SHADOWING") val id = idFor(id) ?: id
        installErrors[id]?.let { return it }
        val entry = available(id)
        val repo = entry?.repo ?: state.installed[id]?.current?.entry?.repo
        return when {
            entry == null && repo != null && fetchErrors[repo] != null -> "Couldn't reach its repo: ${fetchErrors[repo]}"
            entry == null || !installable(id) -> "The repo publishes neither a jar nor a .cs3 for this plugin"
            else -> "Not downloaded yet"
        }
    }

    /**
     * Deleting a repo clears what it provided: its index entries and installed jars (current and rollback versions) go.
     */
    private fun dropRemovedRepos() {
        val s = state
        fun gone(e: Entry) = e.repo == null || e.repo !in urls
        val dropped = s.installed.filterValues { gone(it.current.entry) }
        if (dropped.isEmpty() && s.index.none(::gone) && s.repoNames.keys.all { it in urls } && s.repoIcons.keys.all { it in urls }) return
        dropped.values.forEach { i -> (listOf(i.current) + i.previous).forEach { File(it.file).delete() } }
        if (dropped.isNotEmpty()) log.info("Removed plugins of deleted repos: {}", dropped.keys.sorted())
        save(s.copy(repoNames = s.repoNames.filterKeys { it in urls }, repoIcons = s.repoIcons.filterKeys { it in urls }, index = s.index.filterNot(::gone), installed = s.installed - dropped.keys))
    }

    /**
     * Fetches every repo's index again. A repo that can't be read keeps its saved entries and is reported by [error];
     * throws only when none can be read, leaving the saved index in use.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext
        val results = urls.map { u -> u to runCatching { fetchIndex(u) } }
        fetchErrors.clear()
        for ((u, r) in results) r.exceptionOrNull()?.let { fetchErrors[u] = it.message ?: it.javaClass.simpleName }
        if (results.all { it.second.isFailure }) throw results.first().second.exceptionOrNull()!!
        mutex.withLock {
            val old = state
            val index = results.flatMap { (u, r) -> r.getOrNull()?.entries ?: old.index.filter { it.repo == u } }.distinctBy { it.id }
            val names = old.repoNames + results.mapNotNull { (u, r) -> r.getOrNull()?.name?.let { u to it } }
            val refreshed = results.filter { it.second.isSuccess }.map { it.first }.toSet()
            val icons = old.repoIcons.filterKeys { it !in refreshed } + results.mapNotNull { (u, r) -> r.getOrNull()?.iconUrl?.let { u to it } }
            save(old.copy(repoNames = names.filterKeys { it in urls }, repoIcons = icons.filterKeys { it in urls }, index = index, checkedAt = System.currentTimeMillis()))
            log.info("Plugin repos: {} plugins, {} with jars (the rest are converted from their .cs3)", index.size, index.count { it.jarUrl != null })
        }
        results.forEach { (u, r) -> r.exceptionOrNull()?.let { log.warn("Plugin repo {}: {}", u, it.message) } }
    }

    data class FetchedIndex(val url: String, val name: String?, val iconUrl: String?, val entries: List<Entry>)

    /** Follow redirects and verify that the repository and its plugin lists can be read. */
    suspend fun resolveRepository(url: String): FetchedIndex = withContext(Dispatchers.IO) { fetchIndex(url) }

    suspend fun addPrepared(repo: FetchedIndex) = mutex.withLock {
        val old = state
        save(old.copy(
            repoNames = if (repo.name != null) old.repoNames + (repo.url to repo.name) else old.repoNames,
            repoIcons = if (repo.iconUrl != null) old.repoIcons + (repo.url to repo.iconUrl) else old.repoIcons,
            index = (old.index.filterNot { it.repo == repo.url } + repo.entries.map { it.copy(repo = repo.url) }).distinctBy { it.id },
            checkedAt = System.currentTimeMillis(),
        ))
    }

    private fun fetchIndex(url: String): FetchedIndex {
        val (rootText, rootUrl) = fetchText(url)
        val root = Json.parseToJsonElement(rootText)
        val (name, lists) = if (root is JsonObject && "pluginLists" in root) {
            root["name"]?.jsonPrimitive?.content to root.getValue("pluginLists").jsonArray.map { resolve(rootUrl, it.jsonPrimitive.content) }
        } else {
            null to emptyList()
        }
        val index = (if (root !is JsonObject || "pluginLists" !in root) entries(root, rootUrl) else lists.flatMap { list ->
            val (text, finalUrl) = fetchText(list)
            entries(Json.parseToJsonElement(text), finalUrl)
        }).map { it.copy(repo = url) }
        val icon = (root as? JsonObject)?.get("iconUrl")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { resolve(rootUrl, it) }
        return FetchedIndex(rootUrl, name, icon, index)
    }

    private fun entries(json: JsonElement, base: String): List<Entry> = (json as? JsonArray ?: throw IOException("$base isn't a plugin list"))
        .map { lenient.decodeFromJsonElement<Entry>(it) }
        .map { e -> e.copy(jarUrl = e.jarUrl?.let { resolve(base, it) }, url = e.url?.let { resolve(base, it) }) }

    /** Downloads the repo's version of each of [refs] that isn't installed or is older. Returns the failures by id. */
    suspend fun install(refs: Collection<String>): Map<String, String> = coroutineScope {
        val gate = Semaphore(4)
        refs.map { ref -> async { gate.withPermit { (idFor(ref) ?: ref) to runCatching { install(ref) }.exceptionOrNull() } } }.awaitAll()
            .mapNotNull { (id, e) -> e?.let { id to (it.message ?: it.javaClass.simpleName) } }.toMap()
    }

    /** Installs the repo's version of [ref]; a no-op when that version is already the current one. */
    suspend fun install(ref: String): Unit = withContext(Dispatchers.IO) {
        val entry = available(ref) ?: throw IOException("$ref isn't in the repo")
        val id = entry.id
        val name = entry.internalName
        if (!installable(id)) throw IOException(missingReason(id))
        val current = state.installed[id]?.current
        val same = current != null && current.entry.version == entry.version &&
            current.entry.jarHash == entry.jarHash && current.entry.fileHash == entry.fileHash
        if (same && File(current.file).exists() && !staleConversion(id)) return@withContext
        try {
            val converted = entry.jarUrl == null
            val file = if (converted) convert(entry) else download(entry, entry.jarUrl, entry.jarHash, entry.jarFileSize, "jar")
            mutex.withLock {
                val old = state.installed[id]
                val previous = listOfNotNull(old?.current) + old?.previous.orEmpty()
                val keep = previous.filter { it.entry.version != entry.version && it.file != file.path }.take(KEEP_PREVIOUS)
                (previous - keep.toSet()).forEach { if (it.file != file.path) File(it.file).delete() }
                val jar = Jar(entry, file.path, System.currentTimeMillis(), converted, if (converted) Cs3Converter.VERSION else 0)
                save(state.copy(installed = state.installed + (id to Installed(jar, keep))))
            }
            installErrors.remove(id)
            log.info("Installed {} v{} from {}{}", name, entry.version, entry.repo, if (converted) " (converted from its .cs3)" else "")
        } catch (e: Exception) {
            installErrors[id] = "Install failed: ${e.message ?: e.javaClass.simpleName}"
            throw e
        }
    }

    /** Makes the newest previous version current again. The rolled-back jar is dropped; Update fetches it again. */
    suspend fun rollback(ref: String) = mutex.withLock {
        val id = idFor(ref) ?: throw IllegalArgumentException("$ref isn't installed")
        val old = state.installed[id] ?: throw IllegalArgumentException("$ref isn't installed")
        val back = old.previous.firstOrNull { File(it.file).exists() } ?: throw IllegalArgumentException("$ref has no earlier version to roll back to")
        File(old.current.file).delete()
        save(state.copy(installed = state.installed + (id to Installed(back, old.previous.drop(old.previous.indexOf(back) + 1)))))
        log.info("Rolled {} back to v{}", id, back.entry.version)
    }

    /** Downloads the `.cs3` and converts it; only the jar is kept. */
    private fun convert(entry: Entry): File {
        val cs3 = download(entry, entry.url!!, entry.fileHash, entry.fileSize, "cs3")
        try {
            val jar = File(cs3.path.removeSuffix(".cs3") + "-converted.jar")
            Cs3Converter.convert(cs3, jar)
            return jar
        } finally {
            cs3.delete()
        }
    }

    /** Downloads [url] into the repo's jar folder, checking its size and `sha256-` [hash]. */
    private fun download(entry: Entry, url: String, hash: String?, expectedSize: Long?, ext: String): File {
        val name = entry.internalName
        val expected = hash?.removePrefix("sha256-")?.lowercase() ?: throw IOException("The repo lists no hash for $name's $ext")
        // One folder per repo, so two repos' copies of a plugin never share (and delete) each other's files.
        val folder = File(jarDir, entry.repo?.let(::repoKey) ?: "local").apply { mkdirs() }
        val target = File(folder, "$name-v${entry.version}-${expected.take(12)}.$ext")
        val tmp = File(folder, "${target.name}.part")
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

    private fun fetchText(url: String): Pair<String, String> = http.newCall(Request.Builder().url(url).build()).execute().use { res ->
        if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $url")
        res.body.string() to res.request.url.toString()
    }

    /**
     * Reads the saved state. Entries saved before plugins were kept per repo (no `repo`, installed keyed by bare name)
     * came from the only repo there was then, taken to be the first of [urls].
     */
    private fun load(): State {
        val s = runCatching { lenient.decodeFromString<State>(stateFile.readText()) }.getOrElse { return State() }
        val legacy = urls.firstOrNull()
        fun Entry.owned() = if (repo == null && legacy != null) copy(repo = legacy) else this
        fun Jar.owned() = copy(entry = entry.owned())
        val installed = s.installed.values.associate { i ->
            val current = i.current.owned()
            current.entry.id to Installed(current, i.previous.map { it.owned() })
        }
        return s.copy(index = s.index.map { it.owned() }, installed = installed)
    }

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

        /** The old per-URL folder (single-repo installs). */
        fun legacyDirFor(base: File, url: String): File {
            val id = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
            return File(base, id)
        }

        /** A short, stable id for a repo URL; it names the repo's jar folder and qualifies its plugins' ids. */
        fun repoKey(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

        /** A plugin's id: its name, qualified by its repo when it has one. */
        fun idOf(name: String, repo: String?): String = if (repo == null) name else "$name@${repoKey(repo)}"

        /** All repos share one state file (plugins are keyed by id), so adding a repo keeps what's installed. */
        fun dirFor(base: File, urls: List<String>): File {
            val shared = File(base, "repos")
            val legacy = urls.firstOrNull()?.let { legacyDirFor(base, it) }
            if (!shared.exists() && legacy?.exists() == true) legacy.renameTo(shared)
            return shared
        }

        private fun resolve(base: String, ref: String): String = base.toHttpUrl().resolve(ref)?.toString() ?: ref
    }
}
