package com.istarvin.cloudstream.ui

import desktop.api.HomePage
import desktop.api.HomeRow
import desktop.api.SearchItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeSectionPagerTest {
    private fun page(url: String, next: Boolean = true) = HomePage(
        listOf(HomeRow("Popular", false, listOf(SearchItem("Source", url, url, null, null)))), next,
    )

    @Test fun failedNextPageKeepsTitlesAndRetriesTheSamePage() = runBlocking {
        val pager = HomeSectionPager()
        val requested = mutableListOf<Int>()
        pager.more { requested += it; page("one") }
        pager.more { requested += it; error("Temporary failure") }
        assertEquals(listOf("one"), pager.rows.single().items.map { it.url })
        assertEquals("Temporary failure", pager.error)
        assertFalse(pager.loading)

        pager.more { requested += it; page("two", next = false) }
        pager.more { fail("Must stop after the final page"); page("three") }
        assertEquals(listOf(1, 2, 2), requested)
        assertEquals(listOf("one", "two"), pager.rows.single().items.map { it.url })
        assertNull(pager.error)
    }

    @Test fun simultaneousCarouselsCannotRequestTheSamePageTwice() = runBlocking {
        val pager = HomeSectionPager()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<HomePage>()
        val first = launch {
            pager.more { started.complete(Unit); response.await() }
        }
        started.await()
        pager.more { fail("A request is already in flight"); page("duplicate") }
        response.complete(page("one"))
        first.join()
        assertEquals(1, pager.pages.size)
        assertFalse(pager.loading)
    }

    @Test fun cancellationLeavesPaginationReadyToResume() = runBlocking {
        val pager = HomeSectionPager()
        try {
            pager.more { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(pager.loading)
            assertNull(pager.error)
        }
        pager.more { assertEquals(1, it); page("one") }
        assertEquals(1, pager.pages.size)
    }
}
