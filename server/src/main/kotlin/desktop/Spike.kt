package desktop

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import desktop.plugins.PluginHost
import kotlinx.coroutines.runBlocking

/**
 * Phase 0 check from the command line: `spike <Plugin> <query>` runs search → load → loadLinks on the first
 * provider the plugin registered and prints what comes back.
 */
fun spike(host: PluginHost, plugin: String, query: String) = runBlocking {
    val record = host.plugins.firstOrNull { it.name.equals(plugin, true) } ?: error("No plugin $plugin")
    println("${record.name}: ${record.status} ${record.error.orEmpty()}")
    val provider = host.providers().firstOrNull { it.plugin == record } ?: error("${record.name} registered no providers")
    val api = provider.api
    println("Provider ${api.name} (${api.mainUrl})")

    val results = api.search(query, 1)?.items ?: api.search(query).orEmpty()
    results.take(5).forEach { println("  ${it.type} ${it.name} -> ${it.url}") }
    val first = results.firstOrNull() ?: error("No results")

    val details = api.load(first.url) ?: error("load returned null")
    println("Loaded ${details.name} (${details.javaClass.simpleName}), year ${details.year}")
    val data = when (details) {
        is MovieLoadResponse -> details.dataUrl
        is LiveStreamLoadResponse -> details.dataUrl
        is TvSeriesLoadResponse -> details.episodes.also { println("  ${it.size} episodes") }.first().data
        is AnimeLoadResponse -> details.episodes.values.flatten().also { println("  ${it.size} episodes") }.first().data
        else -> error("Unsupported ${details.javaClass.simpleName}")
    }
    println("loadLinks($data)")
    val ok = api.loadLinks(data, false, { sub ->
        println("  SUB ${sub.lang}: ${sub.url}")
    }) { link ->
        println("  LINK [${link.quality}] ${link.source} / ${link.name} ${link.type}\n       ${link.url}\n       referer=${link.referer} headers=${link.headers}")
    }
    println("loadLinks returned $ok")
}
