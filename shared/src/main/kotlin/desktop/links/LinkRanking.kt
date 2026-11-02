package desktop.links

import desktop.api.LinkDto
import desktop.api.Quality
import desktop.player.SourceProfile

/**
 * Playable links first, then the profile's source + quality priority. Quality ("Auto" just under 1080p), success
 * history and arrival order break ties. The web UI's sources sheet (`web/src/lib/ranking.ts`) and the Android player
 * sort the same way; `testdata/ranking-cases.json` keeps them in step.
 */
object LinkRanking {
    fun quality(q: Int) = if (q == Quality.Unknown.value) 1079 else q

    val comparator: Comparator<LinkDto> = compareByDescending<LinkDto> { it.playable }
        .thenByDescending { quality(it.quality) }
        .thenByDescending { it.score }
        .thenBy { it.order }

    fun comparator(profile: SourceProfile): Comparator<LinkDto> = compareByDescending<LinkDto> { it.playable }
        .thenByDescending { profile.priority(it) }
        .then(comparator)
}
