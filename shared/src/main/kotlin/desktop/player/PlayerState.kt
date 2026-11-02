package desktop.player

import desktop.api.LinkDto
import kotlinx.serialization.Serializable

// What a player reports about itself: the server's mpv, and the web and Android players when they're cast to.

@Serializable
data class Track(val id: Int, val type: String, val title: String?, val lang: String?, val selected: Boolean, val external: Boolean)

/** The next episode's links, resolved in the background near the end of the current one. */
@Serializable
data class NextEpisode(
    val label: String?,
    /** waiting (before 85%) | resolving | ready | failed */
    val status: String,
    /** Playable links found so far. */
    val links: Int = 0,
)

@Serializable
data class PlayerState(
    /** idle | resolving | playing | paused | error */
    val status: String = "idle",
    val message: String? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val volume: Double = 100.0,
    val title: String? = null,
    val episodeLabel: String? = null,
    val provider: String? = null,
    val url: String? = null,
    val data: String? = null,
    val poster: String? = null,
    val link: LinkDto? = null,
    val audioTracks: List<Track> = emptyList(),
    val subTracks: List<Track> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val mpvRunning: Boolean = false,
    val next: NextEpisode? = null,
    /** Links of this item that failed to play this session. */
    val failedLinks: List<String> = emptyList(),
    /** The opening, ending or recap the position is in (AniSkip), while the player offers to skip it. */
    val skip: SkipRange? = null,
)
