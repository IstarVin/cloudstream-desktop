package desktop.links

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import desktop.api.LinkDto
import desktop.api.Mapper
import desktop.api.SubtitleDto
import desktop.db.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    val playable = link !is DrmExtractorLink && link.url.startsWith("http") &&
        link.type in setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)
    val proxied = interceptor != null

    fun dto() = LinkDto(
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
    )
}

class ResolvedSubtitle(val id: String, val sub: SubtitleFile) {
    fun dto() = SubtitleDto(id, sub.lang, sub.url)
}

/** The links of one `loadLinks(data)` call, filling in as the callbacks fire. */
class LinkSet(val provider: String, val data: String) {
    val links = CopyOnWriteArrayList<ResolvedLink>()
    val subtitles = CopyOnWriteArrayList<ResolvedSubtitle>()
    @Volatile var done = false
    @Volatile var error: String? = null
    @Volatile var firstLinkAt: Long? = null
    val created = System.currentTimeMillis()
    lateinit var job: Job

    /** Bumped on every new link, subtitle or completion, so listeners can wait for changes. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    internal fun bump() { _version.value++ }
}

/**
 * Runs `loadLinks` and keeps the results for a few minutes, keyed by (provider, data), so the sources sheet, the mpv
 * source picker and automatic fallback all see the same list. Every link and subtitle gets a short id.
 */
class LinkService(private val scope: CoroutineScope, private val store: Store) {
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
            val stale = System.currentTimeMillis() - s.created > TTL
            val useless = s.done && s.links.none { it.playable }
            if (!fresh && !stale && !useless) return s
        }
        val set = LinkSet(api.name, data)
        sets[key] = set
        set.job = scope.launch {
            try {
                withTimeout(api.loadLinksTimeoutMs ?: 60_000) {
                    api.loadLinks(data, false, { sub -> addSubtitle(set, sub) }) { link -> addLink(api, set, link) }
                }
            } catch (e: Throwable) {
                if (set.links.isEmpty()) set.error = e.message ?: e.javaClass.simpleName
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
        if (set.firstLinkAt == null) set.firstLinkAt = System.currentTimeMillis()
        set.bump()
    }

    private fun addSubtitle(set: LinkSet, sub: SubtitleFile) {
        if (set.subtitles.any { it.sub.url == sub.url }) return
        val r = ResolvedSubtitle(newId("s"), sub)
        subtitles[r.id] = r
        set.subtitles.add(r)
        set.bump()
    }

    /**
     * Playable links, best first: by quality (an adaptive "Auto" HLS counts as just under 1080p), then by how often
     * the source has worked before, then arrival order.
     */
    fun ranked(set: LinkSet): List<ResolvedLink> {
        val scores = store.linkScores(set.provider)
        fun q(l: ResolvedLink) = if (l.link.quality == Qualities.Unknown.value) 1079 else l.link.quality
        return set.links.filter { it.playable }.sortedWith(
            compareByDescending<ResolvedLink> { q(it) }
                .thenByDescending { scores[it.link.source] ?: 0.5 }
                .thenBy { it.order }
        )
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
