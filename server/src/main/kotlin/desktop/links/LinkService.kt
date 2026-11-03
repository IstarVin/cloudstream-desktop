package desktop.links

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import desktop.api.LinkDto
import desktop.api.Mapper
import desktop.api.SubtitleDto
import desktop.api.SubtitleFormat
import desktop.api.describe
import desktop.db.LinkStat
import desktop.db.Store
import desktop.plugins.PluginHost
import desktop.player.SourceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ResolvedLink(
    val id: String,
    val provider: String,
    val link: ExtractorLink,
    /** The provider's `getVideoInterceptor`; when set, mpv must go through the proxy. */
    val interceptor: Interceptor?,
    val order: Int,
) {
    /** For a ClearKey DASH link with one key, the key in hex: mpv's DASH demuxer decrypts with it. */
    val clearKey: String? = (link as? DrmExtractorLink)?.let { ClearKey.hexKey(it, served = interceptor != null) }
    val playable = (link !is DrmExtractorLink || clearKey != null) && link.url.startsWith("http") &&
        link.type in setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)
    val proxied = interceptor != null

    fun dto(stat: LinkStat? = null) = LinkDto(
        id = id,
        source = link.source,
        name = link.name,
        quality = link.quality,
        qualityLabel = Mapper.qualityLabel(link.quality),
        type = link.type.name,
        drm = link is DrmExtractorLink,
        playable = playable,
        proxied = proxied,
        host = runCatching { URI(link.url).host }.getOrNull().orEmpty(),
        order = order,
        score = (stat ?: LinkStat(0, 0)).score,
        successes = stat?.successes ?: 0,
        failures = stat?.failures ?: 0,
    )
}

/** ClearKey (not Widevine) links mpv can play: FFmpeg's DASH demuxer decrypts CENC with a known key. */
object ClearKey {
    private val HEX = Regex("^[0-9a-fA-F]{32}$")

    /**
     * The key of [link] as 32 hex digits when it's a single-key ClearKey DASH link with a real manifest URL; null
     * otherwise. Keys come as hex or base64url (the JWK form plugins use). A manifest carried inline in
     * `extractorData` (StreamCorner's NBA replays) works when the provider's video interceptor serves it at the link's
     * URL ([served]); the proxy then hands it to mpv.
     */
    fun hexKey(link: DrmExtractorLink, served: Boolean = false): String? {
        // `uuid` is a kotlin Uuid, CLEARKEY_UUID a java UUID: they're never equal, so compare the text.
        if (link.uuid.toString() != CLEARKEY_UUID.toString() || link.type != ExtractorLinkType.DASH) return null
        val inline = link.extractorData
        if (!inline.isNullOrEmpty() && !(served && inline.startsWith("<?xml"))) return null
        return toHex(link.key ?: return null)
    }

    fun toHex(key: String): String? {
        val k = key.trim()
        if (HEX.matches(k)) return k.lowercase()
        val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(k.replace('+', '-').replace('/', '_').trimEnd('=')) }.getOrNull()
        return bytes?.takeIf { it.size == 16 }?.joinToString("") { "%02x".format(it) }
    }
}

class ResolvedSubtitle(
    val id: String,
    val sub: SubtitleFile,
    /** Resolved lazily because a provider can emit subtitles before its video links. */
    val interceptor: () -> Interceptor? = { null },
) {
    internal val requestLock = Mutex()
    internal var cached: Pair<SubtitleFormat, ByteArray>? = null
    fun dto() = SubtitleDto(id, sub.lang, sub.url, SubtitleFormat.fromUrl(sub.url)?.extension)
}

/** The links of one `loadLinks(data)` call, filling in as the callbacks fire. */
class LinkSet(val provider: String, val data: String) {
    val links = CopyOnWriteArrayList<ResolvedLink>()
    val subtitles = CopyOnWriteArrayList<ResolvedSubtitle>()
    @Volatile var done = false
    @Volatile var error: String? = null
    val created = System.currentTimeMillis()
    lateinit var job: Job

    /** Old enough that [LinkService.resolve] would run `loadLinks` again (stream URLs expire). */
    val stale get() = System.currentTimeMillis() - created > LinkService.TTL

    /** Bumped on every new link, subtitle or completion, so listeners can wait for changes. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    internal fun bump() { _version.value++ }
}

/**
 * Runs `loadLinks` and keeps the results for a few minutes, keyed by (provider, data), so the sources sheet, the mpv
 * source picker and automatic fallback all see the same list. Every link and subtitle gets a short id.
 */
class LinkService(private val scope: CoroutineScope, private val store: Store, private val host: PluginHost) {
    private val log = LoggerFactory.getLogger(LinkService::class.java)
    private val sets = ConcurrentHashMap<Pair<String, String>, LinkSet>()
    private val links = ConcurrentHashMap<String, ResolvedLink>()
    private val subtitles = ConcurrentHashMap<String, ResolvedSubtitle>()
    private val counter = AtomicInteger()

    private fun newId(prefix: String) = prefix + Integer.toString(counter.incrementAndGet(), 36)

    fun link(id: String) = links[id]
    fun subtitle(id: String) = subtitles[id]
    fun existing(provider: String, data: String) = sets[provider to data]

    /** Returns the cached set for (provider, data), or starts `loadLinks` for it. */
    fun resolve(api: MainAPI, data: String, fresh: Boolean = false): LinkSet {
        expire()
        val key = api.name to data
        sets[key]?.let { s ->
            val useless = s.done && s.links.none { it.playable }
            if (!fresh && !s.stale && !useless) return s
        }
        val set = LinkSet(api.name, data)
        sets[key] = set
        set.job = scope.launch {
            try {
                withTimeout(api.loadLinksTimeoutMs ?: 60_000) {
                    api.loadLinks(data, false, { sub -> addSubtitle(set, sub) }) { link -> addLink(api, set, link) }
                }
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                host.noteFailure(api, e)
                if (set.links.isEmpty()) set.error = describe(e)
                log.info("loadLinks for {} ended: {}", api.name, e.toString())
            } finally {
                set.done = true
                set.bump()
            }
        }
        return set
    }

    private fun addLink(api: MainAPI, set: LinkSet, link: ExtractorLink) {
        val interceptor = runCatching { api.getVideoInterceptor(link) }.getOrNull()
        val r = ResolvedLink(newId("l"), api.name, link, interceptor, set.links.size)
        links[r.id] = r
        set.links.add(r)
        set.bump()
    }

    private fun addSubtitle(set: LinkSet, sub: SubtitleFile) {
        if (set.subtitles.any { it.sub.url == sub.url }) return
        val r = ResolvedSubtitle(newId("s"), sub) { set.links.firstOrNull { it.interceptor != null }?.interceptor }
        subtitles[r.id] = r
        set.subtitles.add(r)
        set.bump()
    }

    /** [link] for the UI, with its source's success history. */
    fun dto(link: ResolvedLink): LinkDto = link.dto(store.linkStats(link.provider)[link.link.source])

    /** Playable links, best first (see [LinkRanking]). */
    fun ranked(set: LinkSet, profile: SourceProfile = SourceProfile()): List<ResolvedLink> {
        val stats = store.linkStats(set.provider)
        return set.links.filter { it.playable }
            .map { it to it.dto(stats[it.link.source]) }
            .sortedWith(compareBy(LinkRanking.comparator(profile)) { it.second })
            .map { it.first }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        sets.entries.removeIf { (_, s) ->
            (now - s.created > TTL * 3 && s.done).also { gone ->
                if (gone) {
                    s.links.forEach { links.remove(it.id) }
                    s.subtitles.forEach { subtitles.remove(it.id) }
                }
            }
        }
    }

    companion object {
        /** Stream URLs often expire; after this a new play re-resolves. */
        const val TTL = 5 * 60_000L
    }
}
