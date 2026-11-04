package com.istarvin.cloudstream.ui

import desktop.api.ProviderDto
import java.text.Collator
import java.util.Locale

// Keep these groups aligned with web/src/lib/content-types.ts.
object ContentTypes {
    data class Group(val id: String, val label: String, val types: Set<String>)

    val groups = listOf(
        Group("anime", "Anime", setOf("Anime", "AnimeMovie", "OVA")),
        Group("movies", "Movies", setOf("Movie", "AnimeMovie")),
        Group("series", "TV Series", setOf("TvSeries")),
        Group("drama", "Asian Drama", setOf("AsianDrama")),
        Group("cartoons", "Cartoons", setOf("Cartoon")),
        Group("documentaries", "Documentaries", setOf("Documentary")),
        Group("live", "Live", setOf("Live")),
        Group("torrent", "Torrents", setOf("Torrent")),
        Group("adult", "Adult", setOf("NSFW")),
        Group("other", "Other", setOf("Others", "Music", "AudioBook", "CustomMedia")),
    )

    fun matches(types: List<String>, filter: String): Boolean =
        groups.firstOrNull { it.id == filter }?.let { g -> types.any { it in g.types } } == true

    fun normalize(filters: Set<String>): Set<String> = filters.intersect(groups.map { it.id }.toSet())

    /** Like the web picker: selected categories form a union; no selection shows every source. */
    fun matchesAny(types: List<String>, filters: Set<String>): Boolean =
        filters.isEmpty() || filters.any { matches(types, it) }

    fun labels(types: List<String>): String =
        groups.filter { matches(types, it.id) }.joinToString(" · ") { it.label }.ifEmpty { "Unspecified content" }
}

fun sourceLanguage(code: String): String {
    if (code.isBlank()) return "Unknown language"
    val locale = Locale.forLanguageTag(code.replace('_', '-'))
    return locale.getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() } ?: code
}

fun filterSources(providers: List<ProviderDto>, categories: Set<String>, query: String): List<ProviderDto> {
    val q = query.trim().lowercase(Locale.ROOT)
    val collator = Collator.getInstance(Locale.ENGLISH)
    return providers.filter { p ->
        ContentTypes.matchesAny(p.types, categories) &&
            "${p.name} ${p.plugin} ${p.lang} ${sourceLanguage(p.lang)}".lowercase(Locale.ROOT).contains(q)
    }.sortedWith { a, b -> collator.compare(a.name, b.name) }
}
