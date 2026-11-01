package desktop.api

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import desktop.config.Settings
import desktop.db.Store
import desktop.plugins.PluginHost
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class NotFound(message: String) : Exception(message)

/** Home pages, search and details, with NSFW filtering applied here so filtered data never reaches the browser. */
class Catalog(private val host: PluginHost, private val settings: Settings, private val store: Store) {
    private class Cached(val response: LoadResponse, val at: Long)

    private val loadCache = ConcurrentHashMap<Pair<String, String>, Cached>()

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
        val res = withTimeout(api.getMainPageTimeoutMs ?: 30_000) {
            api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
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
                    onFailure = { e -> emit(api.name, null, e.message ?: e.javaClass.simpleName) },
                )
            }
        }.forEach { it.await() }
    }

    /** `load()` through a 10-minute cache keyed by (provider, url). */
    suspend fun loadRaw(name: String, url: String): Pair<PluginHost.Provider, LoadResponse> {
        val p = provider(name)
        val key = name to url
        loadCache[key]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }?.let { return p to it.response }
        val r = withTimeout(p.api.loadTimeoutMs ?: 30_000) { p.api.load(url) } ?: throw NotFound("$name returned nothing for $url")
        loadCache[key] = Cached(r, System.currentTimeMillis())
        return p to r
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
