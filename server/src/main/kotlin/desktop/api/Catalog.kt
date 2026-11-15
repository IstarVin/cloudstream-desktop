package desktop.api

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
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
class Catalog(private val host: PluginHost, private val store: Store) {
    private class Cached(val response: LoadResponse, val at: Long)

    private val loadCache = ConcurrentHashMap<Pair<String, String>, Cached>()
    private val LOAD_TTL = 10 * 60_000L

    /** [nsfw] is the calling client's own "show NSFW" choice; the server keeps no global one. */
    fun visibleProviders(nsfw: Boolean): List<PluginHost.Provider> = host.providers().filter { nsfw || !it.nsfw }

    fun provider(name: String, nsfw: Boolean): PluginHost.Provider {
        val p = host.provider(name) ?: throw NotFound("No provider $name")
        if (p.nsfw && !nsfw) throw NotFound("No provider $name")
        return p
    }

    fun providerDtos(nsfw: Boolean) = visibleProviders(nsfw).map { p ->
        ProviderDto(
            name = p.id,
            plugin = p.plugin.id,
            mainUrl = p.api.mainUrl,
            lang = p.api.lang,
            types = p.api.supportedTypes.map { it.name },
            nsfw = p.nsfw,
            hasMainPage = p.api.hasMainPage,
            iconUrl = p.plugin.info.iconUrl,
            installedAt = (host.source as? desktop.plugins.RepoSource)?.installed(p.plugin.id)?.since,
        )
    }.sortedBy { it.name.lowercase() }

    fun homeSections(name: String, nsfw: Boolean): List<HomeSection> {
        val api = provider(name, nsfw).api
        if (!api.hasMainPage) return emptyList()
        // Providers that override getMainPage without mainPageOf get one request with empty data.
        return api.mainPage.ifEmpty { listOf(com.lagradost.cloudstream3.MainPageData("", "", false)) }
            .mapIndexed { i, d -> HomeSection(i, d.name, d.horizontalImages) }
    }

    suspend fun home(name: String, section: Int, page: Int, nsfw: Boolean): HomePage {
        val p = provider(name, nsfw)
        val api = p.api
        val data = api.mainPage.getOrNull(section) ?: com.lagradost.cloudstream3.MainPageData("", "", false)
        val res = guarded(p) {
            withTimeout(api.getMainPageTimeoutMs ?: 30_000) {
                api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
            }
        } ?: return HomePage(emptyList(), false)
        return HomePage(res.items.map { Mapper.homeRow(p.id, it, nsfw) }, res.hasNext)
    }

    /** Searches every visible provider (or [only]) in parallel, calling [emit] as each one finishes. */
    suspend fun search(
        query: String,
        only: Set<String>?,
        nsfw: Boolean,
        emit: suspend (provider: String, items: List<SearchItem>?, error: String?) -> Unit,
    ) = coroutineScope {
        visibleProviders(nsfw).filter { only == null || it.id in only }.map { p ->
            async {
                val api = p.api
                val result = runCatching {
                    withTimeout(api.searchTimeoutMs ?: 20_000) {
                        api.search(query, 1)?.items ?: api.search(query).orEmpty()
                    }
                }
                result.fold(
                    onSuccess = { list -> emit(p.id, list.filter { nsfw || !Mapper.isNsfw(it) }.map { Mapper.searchItem(p.id, it) }, null) },
                    onFailure = { e ->
                        if (e is VirtualMachineError) throw e
                        host.noteFailure(p, e)
                        emit(p.id, null, describe(e))
                    },
                )
            }
        }.forEach { it.await() }
    }

    /** `load()` through a 10-minute cache keyed by (provider, url); expired entries are dropped on each call. */
    suspend fun loadRaw(name: String, url: String, nsfw: Boolean): Pair<PluginHost.Provider, LoadResponse> {
        val p = provider(name, nsfw)
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
            throw PluginFailure("${p.id} took too long", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            host.noteFailure(p, e)
            throw PluginFailure(describe(e), e)
        }

    suspend fun details(name: String, url: String, nsfw: Boolean): Details {
        val (p, r) = loadRaw(name, url, nsfw)
        val d = Mapper.details(p.id, r, p.nsfw, nsfw)
        val progress = store.progressFor(p.id)
        return d.copy(
            episodes = d.episodes.map { e -> progress[e.data]?.let { e.copy(progress = it) } ?: e },
            movieProgress = d.movieData?.let { progress[it] },
            bookmarked = store.bookmarkList(p.id, url),
        )
    }
}
