package desktop

import com.lagradost.cloudstream3.SubtitleFile
import desktop.links.ResolvedSubtitle
import desktop.player.SubtitlePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitlePreferenceTest {
    @Suppress("DEPRECATION")
    private fun sub(id: String, label: String) = ResolvedSubtitle(id, SubtitleFile(label, "https://example.com/$id"))

    @Test
    fun matchesSourceLabelsAtWordBoundaries() {
        assertTrue(SubtitlePreference.matchesSource("English (Reanime)", "Reanime HD-1 SUB", ""))
        assertTrue(SubtitlePreference.matchesSource("English [AnimeX]", "other", "animex SUB"))
        assertFalse(SubtitlePreference.matchesSource("English (Anime)", "AnimeX SUB", ""))
        assertFalse(SubtitlePreference.matchesSource("English", "Reanime HD-1 SUB", ""))
    }

    @Test
    fun waitsForMatchingSubtitlesAndFallsBackAfterResolution() {
        val other = sub("1", "English (AnimeX)")
        val matching = sub("2", "English (Reanime)")
        assertEquals(emptyList(), SubtitlePreference.order(listOf(other), "Reanime HD-1", "", false))
        assertEquals(listOf(matching), SubtitlePreference.order(listOf(other, matching), "Reanime HD-1", "", false))
        assertEquals(listOf(matching, other), SubtitlePreference.order(listOf(other, matching), "Reanime HD-1", "", true))
        assertEquals(listOf(other), SubtitlePreference.order(listOf(other), "Reanime HD-1", "", true))
    }
}
