package com.istarvin.cloudstream.ui

import desktop.api.HomePage
import desktop.api.HomeRow
import desktop.api.SearchItem
import org.junit.Assert.*
import org.junit.Test

class HomePagesTest {
    private fun item(url: String, provider: String = "Source") = SearchItem(provider, url, url, null, null)
    private fun page(vararg items: SearchItem, next: Boolean = true) =
        HomePage(listOf(HomeRow("Popular", false, items.toList())), next)

    @Test fun missingCollectionNeverShowsAnotherCollectionsTitles() {
        val rows = HomePages.merge(listOf(page(item("one"))))
        assertTrue(HomePages.rowItems(rows, "Recently added").isEmpty())
        assertEquals(listOf(item("one")), HomePages.rowItems(rows, "Popular"))
    }

    @Test fun paginationDeduplicatesPerProviderAndStopsOnRepeatedPages() {
        val first = page(item("one"))
        val second = page(item("one"), item("two"), item("one", "Other"))
        val pages = listOf(first, second)
        assertEquals(3, HomePages.merge(pages).single().items.size)
        assertTrue(HomePages.hasMore(pages))
        assertFalse(HomePages.hasMore(pages + second))
        assertFalse(HomePages.hasMore(listOf(page(item("one"), next = false))))
    }
}
