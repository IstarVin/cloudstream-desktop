package desktop.api

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import desktop.config.Settings
import desktop.db.Store
import desktop.plugins.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class NotFound(message: String) : Exception(message)

/** A provider call that failed; the message is already user-facing (see [describe]). */
class PluginFailure(message: String, cause: Throwable) : Exception(message, cause)

/** Home pages, search and details, with NSFW filtering applied here so filtered data never reaches the browser. */
class Catalog(private val host: PluginHost, private val settings: Settings, private val store: Store) {
    private class Cached(val response: LoadResponse, val at: Long)

    private val loadCache = ConcurrentHashMap<Pair<String, String>, Cached>()
    private val LOAD_TTL = 10 * 60_000L

    fun visibleProviders(): List<PluginHost.Provider> {
        val nsfw = settings.showNsfw
        return host.providers().filter { nsfw || !it.nsfw }
    }

    fun provider(name: String): PluginHost.Provider {
        val p = host.provider(name) ?: throw NotFound("No provider $name")
        if (p.nsfw && !settings.showNsfw) throw NotFound("No provider $name")
        return p
    }

    fun providerDtos() = visibleProviders().map { p ->
        ProviderDto(
            name = p.api.name,
            plugin = p.plugin.name,
            mainUrl = p.api.mainUrl,
            lang = p.api.lang,
            types = p.api.supportedTypes.map { it.name },
            nsfw = p.nsfw,
            hasMainPage = p.api.hasMainPage,
            iconUrl = p.plugin.info.iconUrl,
        )
    }.sortedBy { it.name.lowercase() }

    fun homeSections(name: String): List<HomeSection> {
        val api = provider(name).api
        if (!api.hasMainPage) return emptyList()
        // Providers that override getMainPage without mainPageOf get one request with empty data.
        return api.mainPage.ifEmpty { listOf(com.lagradost.cloudstream3.MainPageData("", "", false)) }
            .mapIndexed { i, d -> HomeSection(i, d.name, d.horizontalImages) }
    }

    suspend fun home(name: String, section: Int, page: Int): HomePage {
        val p = provider(name)
        val api = p.api
        val data = api.mainPage.getOrNull(section) ?: com.lagradost.cloudstream3.MainPageData("", "", false)
        val res = guarded(p) {
            withTimeout(api.getMainPageTimeoutMs ?: 30_000) {
                api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
            }
        } ?: return HomePage(emptyList(), false)
        val nsfw = settings.showNsfw
        return HomePage(res.items.map { Mapper.homeRow(api.name, it, nsfw) }, res.hasNext)
    }

    /** Searches every visible provider (or [only]) in parallel, calling [emit] as each one finishes. */
    suspend fun search(
        query: String,
        only: Set<String>?,
        emit: suspend (provider: String, items: List<SearchItem>?, error: String?) -> Unit,
    ) = coroutineScope {
        val nsfw = settings.showNsfw
        visibleProviders().filter { only == null || it.api.name in only }.map { p ->
            async {
                val api = p.api
                val result = runCatching {
                    withTimeout(api.searchTimeoutMs ?: 20_000) {
                        api.search(query, 1)?.items ?: api.search(query).orEmpty()
                    }
                }
                result.fold(
                    onSuccess = { list -> emit(api.name, list.filter { nsfw || !Mapper.isNsfw(it) }.map { Mapper.searchItem(api.name, it) }, null) },
                    onFailure = { e ->
                        if (e is VirtualMachineError) throw e
                        host.noteFailure(p, e)
                        emit(api.name, null, describe(e))
                    },
                )
            }
        }.forEach { it.await() }
    }

    /** `load()` through a 10-minute cache keyed by (provider, url); expired entries are dropped on each call. */
    suspend fun loadRaw(name: String, url: String): Pair<PluginHost.Provider, LoadResponse> {
        val p = provider(name)
        val key = name to url
        val now = System.currentTimeMillis()
        loadCache.values.removeIf { now - it.at >= LOAD_TTL }
        loadCache[key]?.let { return p to it.response }
        val r = guarded(p) { withTimeout(p.api.loadTimeoutMs ?: 30_000) { p.api.load(url) } } ?: throw NotFound("$name returned nothing for $url")
        loadCache[key] = Cached(r, now)
        return p to r
    }

    /** Runs a call into [p]'s plugin, recording linkage errors, and rethrows failures as [PluginFailure]. */
    private suspend fun <T> guarded(p: PluginHost.Provider, block: suspend () -> T): T =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            throw PluginFailure("${p.api.name} took too long", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            host.noteFailure(p, e)
            throw PluginFailure(describe(e), e)
        }

    suspend fun details(name: String, url: String): Details {
        val (p, r) = loadRaw(name, url)
        val d = Mapper.details(p.api.name, r, p.nsfw, settings.showNsfw)
        val progress = store.progressFor(p.api.name)
        return d.copy(
            episodes = d.episodes.map { e -> progress[e.data]?.let { e.copy(progress = it) } ?: e },
            movieProgress = d.movieData?.let { progress[it] },
            bookmarked = store.bookmarkList(p.api.name, url),
        )
    }
}
