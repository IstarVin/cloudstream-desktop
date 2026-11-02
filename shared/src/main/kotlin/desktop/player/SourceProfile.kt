package desktop.player

import desktop.api.LinkDto
import desktop.api.Quality
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Cloudstream's source + nearest-quality priority model. Source names are exact, case-sensitive labels. */
@Serializable
data class SourceProfile(
    val id: Int = 1,
    val name: String = "Profile $id",
    val sourcePriorities: Map<String, Int> = emptyMap(),
    val qualityPriorities: Map<Int, Int> = emptyMap(),
    val hideFailedSources: Boolean = false,
    val hideNegativeSources: Boolean = false,
) {
    fun priority(source: String, quality: Int): Int {
        val nearest = Quality.entries.minBy { abs(it.value.toLong() - quality) }
        return (sourcePriorities[source] ?: 1) + (qualityPriorities[nearest.value] ?: nearest.defaultPriority)
    }

    fun priority(link: LinkDto) = priority(link.source, link.quality)

    fun visible(id: String, priority: Int, failed: Set<String>, current: String?): Boolean =
        id == current || ((!hideNegativeSources || priority >= 0) && (!hideFailedSources || id !in failed))

    fun validate() {
        require(id in 1..COUNT) { "Profile id must be between 1 and $COUNT" }
        require(name.trim().length in 1..80) { "Profile name must contain 1 to 80 characters" }
        require(sourcePriorities.keys.all { it.isNotBlank() && it.length <= 200 }) { "Invalid source name" }
        require(qualityPriorities.keys.all { q -> Quality.entries.any { it.value == q } }) { "Unknown quality" }
        require((sourcePriorities.values + qualityPriorities.values).all { it in -1000..1000 }) { "Priority must be between -1000 and 1000" }
    }

    companion object {
        const val COUNT = 7
        const val AUTO_PLAY_PRIORITY = 10
        fun defaults() = (1..COUNT).map { SourceProfile(id = it) }
    }
}
