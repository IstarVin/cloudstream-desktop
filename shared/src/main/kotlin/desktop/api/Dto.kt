package desktop.api

import desktop.plugins.SettingField
import kotlinx.serialization.Serializable

// Stable shapes for the web UI, the mpv script and the Android app. Nothing here mirrors Cloudstream's classes directly, so a library
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
    /** References into the runtime that won't link, found at load (see LinkageCheck). */
    val linkage: List<String> = emptyList(),
    /** The first linkage error a call into the plugin hit. */
    val runtimeLinkage: String? = null,
    /** Repo mode: the version the repo offers (when it publishes a desktop jar). */
    val available: Int? = null,
    /** Repo mode: the version a rollback would restore. */
    val previous: Int? = null,
    /** Labelled settings the plugin declares; the form shows any other keys below them. */
    val settingsSchema: List<SettingField> = emptyList(),
    /** Icon declared by the plugin source; available even when the plugin is disabled. */
    val iconUrl: String? = null,
)

@Serializable
data class PluginSourceDto(
    /** `dev` (the local extensions repo, built with gradle) or `repo` (published jars). */
    val mode: String,
    val devPath: String,
    val devAvailable: Boolean,
    val repoUrl: String,
    val repoName: String? = null,
    /** When the repo's index was last read, or 0. */
    val checkedAt: Long = 0,
    val error: String? = null,
    /** Installed plugins with a newer version in the repo. */
    val updates: List<String> = emptyList(),
)

@Serializable
data class UpdateResult(val failed: Map<String, String>, val source: PluginSourceDto)

@Serializable
data class BrowserCheckDto(val id: String, val kind: String, val host: String, val url: String, val since: Long, val solving: Boolean)

@Serializable
data class BrowserStatusDto(
    val running: Boolean,
    /** The mode Chromium is running in (a manual check switches it to `headed`). */
    val mode: String?,
    val configuredMode: String,
    val executable: String?,
    val userAgent: String?,
    val error: String?,
    val checks: List<BrowserCheckDto>,
    /** Hosts with a saved Cloudflare clearance. */
    val clearances: List<String>,
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
data class Progress(val position: Double, val duration: Double, val watched: Boolean, val updated: Long)

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
    /** MyAnimeList and AniList ids, when the provider sets them (anime); used for AniSkip. */
    val malId: Int? = null,
    val aniListId: Int? = null,
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
    /** Arrival order within its episode's links. */
    val order: Int = 0,
    /** The source's success ratio for this provider, smoothed so a new source starts at 0.5. */
    val score: Double = 0.5,
    val successes: Int = 0,
    val failures: Int = 0,
)

@Serializable
data class SubtitleDto(
    val id: String,
    val lang: String,
    val url: String,
    /** ass | vtt | srt | ttml, from the URL; null when it doesn't say (`/api/subtitle` sniffs the content). */
    val format: String? = null,
)

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

@Serializable
data class StatusDto(
    val building: Boolean,
    val plugins: Int,
    val loaded: Int,
    val providers: Int,
    val mpvRunning: Boolean,
    /** Whether the server can play in mpv (off on a headless host such as Docker). */
    val mpvAvailable: Boolean = false,
    val authed: Boolean,
    val loopback: Boolean,
)

@Serializable
data class AuthStatus(val authed: Boolean, val loopback: Boolean)

/**
 * Marks an episode watched or not, or (with [position]) saves how far a client's own player got. Saving a position
 * also records the title in the history, as playing it in mpv does.
 */
@Serializable
data class ProgressRequest(
    val provider: String,
    val url: String,
    val data: String,
    val watched: Boolean = false,
    val position: Double? = null,
    val duration: Double? = null,
    val label: String? = null,
)

@Serializable
data class ResumeDto(val position: Double?)

/** A link a client played (or failed to), for its source's success history. */
@Serializable
data class LinkReport(val link: String, val success: Boolean)

/** A ClearKey content key, as the 32-hex-digit key id and key. */
@Serializable
data class ClearKeyDto(val kid: String?, val key: String)

/** What a client's own player opens for a link: usually a server proxy path, relative to the server root. */
@Serializable
data class StreamDto(
    val url: String,
    /** VIDEO | M3U8 | DASH */
    val type: String,
    /** Request headers the player must send; empty for proxied URLs (the proxy adds them). */
    val headers: Map<String, String> = emptyMap(),
    val proxied: Boolean,
    val clearKey: ClearKeyDto? = null,
)

/** A text subtitle track muxed into a Matroska stream. [header] is the ASS script header for an ASS/SSA track. */
@Serializable
data class EmbeddedTrackDto(
    val number: Long,
    val codec: String,
    val ass: Boolean,
    val lang: String? = null,
    val name: String? = null,
    val header: String? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
)

/** One block of an embedded track: plain or WebVTT cue text, or for ASS the event line without its times. */
@Serializable
data class EmbeddedCueDto(val track: Long, val start: Long, val duration: Long, val text: String)

/**
 * Embedded subtitles read so far from a proxied stream. [matroska] is null while unknown and false when the stream
 * isn't Matroska (nothing will ever come). [tracks] is sent only when the client's count is out of date.
 */
@Serializable
data class EmbeddedSubsDto(
    val matroska: Boolean?,
    val version: Int,
    val next: Int,
    val tracks: List<EmbeddedTrackDto>? = null,
    val cues: List<EmbeddedCueDto> = emptyList(),
)
