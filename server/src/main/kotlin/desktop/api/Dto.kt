package desktop.api

import kotlinx.serialization.Serializable

// Stable shapes for the web UI and the mpv script. Nothing here mirrors Cloudstream's classes directly, so a library
// update can't change what the UI sees.

@Serializable
data class ProviderDto(
    val name: String,
    val plugin: String,
    val mainUrl: String,
    val lang: String,
    val types: List<String>,
    val nsfw: Boolean,
    val hasMainPage: Boolean,
    val iconUrl: String?,
)

@Serializable
data class PluginDto(
    val name: String,
    val version: Int?,
    val description: String?,
    val status: String,
    val error: String?,
    val nsfw: Boolean,
    val tvTypes: List<String>,
    val enabled: Boolean,
    val providers: List<String>,
    val lastUsed: Long,
    /** Preference files the plugin opened; the settings form edits these. */
    val settingsFiles: List<String>,
)

@Serializable
data class SearchItem(
    val provider: String,
    val name: String,
    val url: String,
    val type: String?,
    val poster: String?,
    val posterHeaders: Map<String, String>? = null,
    val quality: String? = null,
    val year: Int? = null,
    val score: Double? = null,
)

@Serializable
data class HomeSection(val index: Int, val name: String, val horizontal: Boolean)

@Serializable
data class HomeRow(val name: String, val horizontal: Boolean, val items: List<SearchItem>)

@Serializable
data class HomePage(val rows: List<HomeRow>, val hasNext: Boolean)

@Serializable
data class Actor(val name: String, val image: String?, val role: String?)

@Serializable
data class Season(val season: Int, val name: String?, val displaySeason: Int?)

@Serializable
data class Episode(
    val data: String,
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val poster: String?,
    val description: String?,
    val date: Long?,
    val runtime: Int?,
    /** "Dubbed"/"Subbed" for anime, null otherwise. */
    val dub: String? = null,
    val progress: Progress? = null,
)

@Serializable
data class Progress(val position: Double, val duration: Double, val watched: Boolean)

@Serializable
data class Details(
    val provider: String,
    val url: String,
    val name: String,
    val type: String,
    /** movie | series | anime | live | torrent | other */
    val kind: String,
    val poster: String?,
    val posterHeaders: Map<String, String>? = null,
    val background: String?,
    val logo: String?,
    val plot: String?,
    val year: Int?,
    val tags: List<String>,
    val score: Double?,
    val duration: Int?,
    val contentRating: String?,
    val showStatus: String?,
    val actors: List<Actor>,
    val trailers: List<String>,
    val recommendations: List<SearchItem>,
    val seasons: List<Season>,
    val episodes: List<Episode>,
    /** For movies and live streams: the `data` passed to loadLinks. */
    val movieData: String?,
    val movieProgress: Progress? = null,
    val nsfw: Boolean,
    val bookmarked: String? = null,
)

@Serializable
data class LinkDto(
    val id: String,
    val source: String,
    val name: String,
    val quality: Int,
    val qualityLabel: String,
    /** VIDEO | M3U8 | DASH | TORRENT | MAGNET */
    val type: String,
    val drm: Boolean,
    val playable: Boolean,
    /** Played through the header-injecting proxy instead of directly. */
    val proxied: Boolean,
    val host: String,
)

@Serializable
data class SubtitleDto(val id: String, val lang: String, val url: String)

@Serializable
data class LinkEvent(val provider: String, val data: String, val link: LinkDto? = null, val subtitle: SubtitleDto? = null)

@Serializable
data class HistoryEntry(
    val provider: String,
    val url: String,
    val title: String,
    val poster: String?,
    val type: String?,
    val lastWatched: Long,
)

@Serializable
data class ContinueEntry(
    val provider: String,
    val url: String,
    val title: String,
    val poster: String?,
    val episodeData: String,
    val episodeLabel: String?,
    val position: Double,
    val duration: Double,
    val updated: Long,
)

@Serializable
data class Bookmark(
    val provider: String,
    val url: String,
    val title: String,
    val poster: String?,
    val type: String?,
    val list: String,
    val added: Long,
)

@Serializable
data class BookmarkRequest(
    val provider: String,
    val url: String,
    val title: String = "",
    val poster: String? = null,
    val type: String? = null,
    /** watching | planned | completed | dropped; null removes the bookmark. */
    val list: String? = null,
)

/** What to play: an episode (or movie) of a title. The server resolves links and picks one unless [linkId] is set. */
@Serializable
data class PlayRequest(
    val provider: String,
    val url: String,
    val data: String,
    val linkId: String? = null,
    val subtitleIds: List<String> = emptyList(),
    val startAt: Double? = null,
)

@Serializable
data class PlayerCommand(
    val seconds: Double? = null,
    val relative: Boolean = false,
    val type: String? = null,
    val id: String? = null,
    val value: Double? = null,
    val linkId: String? = null,
)

@Serializable
data class PluginSettingsDto(val files: Map<String, List<PrefEntry>>)

@Serializable
data class PrefEntry(
    val key: String,
    /** string | boolean | int | long | float | stringSet */
    val type: String,
    val value: String?,
    /** Default the plugin passed when it last read the key. */
    val default: String? = null,
)

@Serializable
data class PairingInfo(val url: String, val token: String, val lanUrls: List<String>)

@Serializable
data class SearchResults(val provider: String, val items: List<SearchItem>)
