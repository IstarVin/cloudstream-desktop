package desktop

import desktop.api.Details
import desktop.api.Episode
import desktop.api.Progress
import desktop.player.NextUp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextUpTest {
    @Serializable data class CaseProgress(val position: Double, val watched: Boolean)
    @Serializable data class CaseEpisode(val data: String, val dub: String? = null, val progress: CaseProgress? = null)
    @Serializable data class Case(val name: String, val episodes: List<CaseEpisode>, val expected: String?)
    @Serializable data class Cases(val cases: List<Case>)

    @Test
    fun picksLikeTheSharedCases() {
        // The web UI's tests read the same file (web/src/lib/next-up.test.ts).
        val cases = Json.decodeFromString<Cases>(File("../testdata/next-up-cases.json").readText()).cases
        for (c in cases) {
            val episodes = c.episodes.map {
                Episode(it.data, null, null, null, null, null, null, null, it.dub, it.progress?.let { p -> Progress(p.position, 1500.0, p.watched, 0) })
            }
            val dub = NextUp.defaultDub(episodes)
            assertEquals(c.expected, NextUp.pick(episodes.filter { dub == null || it.dub == dub })?.data, c.name)
        }
    }

    private fun details(episodes: List<Episode>, movieData: String?) = Details(
        "P", "u", "Title", "Movie", "movie", null, null, null, null, null, null, emptyList(), null, null, null, null,
        emptyList(), emptyList(), emptyList(), emptyList(), episodes, movieData, nsfw = false,
    )
    private fun ep(data: String, dub: String? = null, watched: Boolean = false) =
        Episode(data, null, null, null, null, null, null, null, dub, if (watched) Progress(1200.0, 1500.0, true, 0) else null)

    @Test
    fun dataPlaysTheMovieOrTheNextUpEpisode() {
        assertEquals("movie", NextUp.data(details(emptyList(), "movie")))
        assertNull(NextUp.data(details(emptyList(), null)), "nothing to play")
        val eps = listOf(ep("d1", "Dubbed", watched = true), ep("s1", "Subbed", watched = true), ep("s2", "Subbed"), ep("d2", "Dubbed"))
        assertEquals("s2", NextUp.data(details(eps, "ignored")), "episodes win over movieData, in the subbed version")
    }
}
