package com.istarvin.cloudstream.ui

import desktop.api.ProviderDto
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFiltersTest {
    private fun source(name: String, vararg types: String, plugin: String = "Test plugin", lang: String = "en") =
        ProviderDto(name, plugin, "https://example.test", lang, types.toList(), false, true, null)

    private val anime = source("Anime source", "Anime", lang = "ja")
    private val movie = source("Movie source", "Movie")
    private val mixed = source("Mixed source", "AnimeMovie", "TvSeries")
    private val unknown = source("Unknown source")
    private val sources = listOf(movie, unknown, mixed, anime)

    @Test fun noCategoriesIncludesUnspecifiedSourcesAndSortsByName() {
        assertEquals(listOf(anime, mixed, movie, unknown), filterSources(sources, emptySet(), ""))
    }

    @Test fun categoriesMatchAnySelectedTypeWithoutDuplicates() {
        assertEquals(listOf(anime, mixed, movie), filterSources(sources, setOf("anime", "movies"), ""))
    }

    @Test fun deselectingACategoryKeepsTheOtherCategoryActive() {
        val selected = setOf("anime", "movies") - "anime"
        assertEquals(listOf(mixed, movie), filterSources(sources, selected, ""))
    }

    @Test fun searchCombinesWithCategories() {
        assertEquals(listOf(mixed), filterSources(sources, setOf("anime"), " MIXED "))
        assertEquals(emptyList<ProviderDto>(), filterSources(sources, setOf("anime"), "Movie source"))
    }

    @Test fun searchFindsPluginAndLanguageNameOrCode() {
        assertEquals(listOf(anime), filterSources(sources, emptySet(), "Japanese"))
        assertEquals(listOf(anime), filterSources(sources, emptySet(), "ja"))
        assertEquals(listOf(anime, mixed, movie, unknown), filterSources(sources, emptySet(), "test PLUGIN"))
    }

    @Test fun invalidAndLegacyAllCategoriesDoNotBecomeFilters() {
        assertEquals(setOf("anime"), ContentTypes.normalize(setOf("all", "anime", "missing")))
        assertEquals(emptySet<String>(), ContentTypes.normalize(setOf("all")))
    }
}
