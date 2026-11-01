package desktop

import desktop.db.Database
import desktop.db.Store
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val store = Store(Database(File(createTempDirectory().toFile(), "test.db")))

    @Test
    fun resumesOnlyBetweenTheFirst30SecondsAnd90Percent() {
        store.saveProgress("P", "u", "early", null, 20.0, 1000.0, false)
        store.saveProgress("P", "u", "middle", null, 400.0, 1000.0, false)
        store.saveProgress("P", "u", "late", null, 950.0, 1000.0, false)
        store.saveProgress("P", "u", "live", null, 400.0, 0.0, false)
        assertNull(store.resumePosition("P", "early"))
        assertEquals(400.0, store.resumePosition("P", "middle"))
        assertNull(store.resumePosition("P", "late"))
        assertEquals(400.0, store.resumePosition("P", "live"), "no duration (live): resume anyway")
        assertNull(store.resumePosition("P", "unknown"))
    }

    @Test
    fun watchedStaysWatchedUntilUnmarked() {
        store.saveProgress("P", "u", "e1", null, 980.0, 1000.0, true)
        store.saveProgress("P", "u", "e1", null, 100.0, 1000.0, false)
        assertTrue(store.progress("P", "e1")!!.watched, "a later partial play doesn't unwatch")
        assertNull(store.resumePosition("P", "e1"))
        store.setWatched("P", "u", "e1", false)
        assertEquals(100.0, store.resumePosition("P", "e1"))
    }

    @Test
    fun continueWatchingShowsTheLatestUnfinishedEpisodePerTitle() {
        store.touchHistory("P", "show", "Show", null, "TvSeries", false)
        store.touchHistory("P", "film", "Film", null, "Movie", false)
        store.saveProgress("P", "show", "s1e1", "S1E1", 300.0, 1400.0, false)
        Thread.sleep(5)
        store.saveProgress("P", "show", "s1e2", "S1E2", 200.0, 1400.0, false)
        Thread.sleep(5)
        store.saveProgress("P", "film", "film", null, 10.0, 6000.0, false)

        val entries = store.continueWatching(showNsfw = false)
        assertEquals(listOf("s1e2"), entries.map { it.episodeData }, "one entry per title; the film is under 30 s")
        assertEquals("S1E2", entries.single().episodeLabel)
    }

    @Test
    fun nsfwEntriesAreFilteredAndDeletingHistoryDropsProgress() {
        store.touchHistory("P", "a", "A", null, "Movie", false)
        store.touchHistory("N", "b", "B", null, "NSFW", true)
        store.saveProgress("N", "b", "b", null, 100.0, 1000.0, false)
        assertEquals(listOf("a"), store.history(showNsfw = false).map { it.url })
        assertEquals(setOf("a", "b"), store.history(showNsfw = true).map { it.url }.toSet())
        assertTrue(store.continueWatching(showNsfw = false).isEmpty())

        store.deleteHistory("N", "b")
        assertNull(store.progress("N", "b"))
    }

    @Test
    fun linkScoresAreSmoothedSuccessRatios() {
        assertTrue(store.linkScores("P").isEmpty())
        repeat(3) { store.recordLink("P", "Good", success = true) }
        store.recordLink("P", "Good", success = false)
        store.recordLink("P", "Bad", success = false)
        val scores = store.linkScores("P")
        assertEquals(4.0 / 6.0, scores.getValue("Good"), 1e-9)
        assertEquals(1.0 / 3.0, scores.getValue("Bad"), 1e-9)
    }

    @Test
    fun bookmarksMoveBetweenListsAndCanBeRemoved() {
        store.setBookmark("P", "u", "T", null, "Movie", false, "planned")
        store.setBookmark("P", "u", "T", null, "Movie", false, "watching")
        assertEquals("watching", store.bookmarkList("P", "u"))
        assertEquals(1, store.bookmarks(showNsfw = false).size)
        store.setBookmark("P", "u", "T", null, "Movie", false, null)
        assertNull(store.bookmarkList("P", "u"))
    }
}
