package desktop.player

import kotlinx.serialization.Serializable

/**
 * When auto-play picks a link, as in Cloudstream: as soon as a link reaches [SourceProfile.AUTO_PLAY_PRIORITY], or
 * right away on fallback. Otherwise it keeps waiting while links resolve, and takes the best one once they're all in.
 */
object AutoPlay {
    fun ready(fallback: Boolean, done: Boolean, bestPriority: Int): Boolean =
        fallback || done || bestPriority >= SourceProfile.AUTO_PLAY_PRIORITY
}

/** Source labels are supplied by some aggregate providers as a suffix, e.g. "English (Reanime)". */
object SubtitlePreference {
    private val sourceLabel = Regex("[\\(\\[]([^()\\[\\]]+)[)\\]]\\s*$")

    fun matchesSource(language: String, source: String, name: String): Boolean {
        val label = sourceLabel.find(language)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return listOf(source, name).any { value ->
            value.equals(label, ignoreCase = true) ||
                (value.startsWith(label, ignoreCase = true) && value.getOrNull(label.length)?.isLetterOrDigit() == false)
        }
    }

    /** Subtitles labelled for this link's source first; the rest only once the links are [done]. */
    fun <T> order(subtitles: List<T>, source: String, name: String, done: Boolean, lang: (T) -> String): List<T> {
        val matching = subtitles.filter { matchesSource(lang(it), source, name) }
        return if (done) matching + subtitles.filterNot { it in matching } else matching
    }

    private val preferred = listOf("en", "eng", "english")

    /** In a preferred language (English for now), by label or tag. */
    fun preferredLanguage(lang: String, tag: String? = null): Boolean =
        preferred.any { p -> lang.lowercase().startsWith(p) || tag?.lowercase() == p }
}

/** A part of an episode the player can skip: an opening, ending or recap. */
@Serializable
data class SkipRange(
    /** op | ed | recap */
    val type: String,
    val start: Double,
    val end: Double,
) {
    val label get() = when (type) { "op" -> "Opening"; "ed" -> "Ending"; else -> "Recap" }
}

/** Which range the position is in, and when auto-skip seeks past one (once per range per file). */
object SkipRule {
    /** The range [pos] is inside, ignoring its last second so a seek to the end doesn't count. */
    fun current(ranges: List<SkipRange>, pos: Double): SkipRange? = ranges.firstOrNull { pos >= it.start && pos < it.end - 1 }

    /** The range auto mode should skip now, or null; [skipped] holds the ranges already skipped in this file. */
    fun autoSkip(ranges: List<SkipRange>, pos: Double, skipped: Set<SkipRange>): SkipRange? = current(ranges, pos)?.takeIf { it !in skipped }
}

/** Progress rules shared by the mpv session and the phone's player. */
object Watched {
    /** Played this far, an episode counts as watched. */
    const val FRACTION = 0.9

    /** Near the end the next episode's links are resolved ahead. */
    const val PREFETCH_FRACTION = 0.85

    /** A link that has played this long counts as a success for its source. */
    const val SUCCESS_AFTER_SECONDS = 15.0

    fun watched(position: Double, duration: Double) = duration > 0 && position >= duration * FRACTION
}
