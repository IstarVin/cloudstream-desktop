package desktop.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import desktop.player.SourceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The server's settings the app reads (`/api/settings`); the desktop-only ones are ignored. */
@Serializable
data class ServerSettings(
    @SerialName("source_profile") val sourceProfile: Int = 1,
    @SerialName("source_profiles") val sourceProfiles: List<SourceProfile> = SourceProfile.defaults(),
    @SerialName("autoplay_best") val autoplayBest: Boolean = true,
    @SerialName("autoplay_next") val autoplayNext: Boolean = true,
    @SerialName("show_nsfw") val showNsfw: Boolean = false,
    @SerialName("proxy_all") val proxyAll: Boolean = false,
    /** prompt | auto | off */
    @SerialName("skip_segments") val skipSegments: String = "prompt",
    @SerialName("mpv_extra_args") val mpvExtraArgs: List<String> = emptyList(),
    /** headless | xvfb | headed */
    @SerialName("chromium_mode") val chromiumMode: String = "headless",
) {
    val activeProfile: SourceProfile get() = sourceProfiles.firstOrNull { it.id == sourceProfile } ?: SourceProfile(sourceProfile)
}

/** How the phone's player behaves; kept on the phone, not the server. */
data class PlayerPrefs(
    /** Seconds a double tap seeks. */
    val seekStep: Int = 10,
    /** Speed while the screen is held. */
    val holdSpeed: Float = 2f,
    /** Subtitle text size, relative to the default. */
    val subtitleScale: Float = 1f,
    /** Draw SRT/VTT subtitles on a dark box instead of with an outline. */
    val subtitleBackground: Boolean = false,
    /** Lift subtitles off the bottom edge, as a fraction of the height. */
    val subtitleBottom: Float = 0.06f,
    val brightnessGesture: Boolean = true,
    val volumeGesture: Boolean = true,
    val swipeSeek: Boolean = true,
    /** Play link URLs directly instead of through the desktop's proxy when nothing needs it. */
    val directLinks: Boolean = false,
    /** 0 fit, 1 fill, 2 zoom. */
    val resizeMode: Int = 0,
    val pictureInPicture: Boolean = true,
)

private val Context.store by preferencesDataStore("prefs")

class Prefs(private val context: Context) {
    private object K {
        val server = stringPreferencesKey("server")
        val homeProvider = stringPreferencesKey("home_provider")
        val homeType = stringPreferencesKey("home_type")
        val homeCategories = stringSetPreferencesKey("home_categories")
        val seekStep = intPreferencesKey("seek_step")
        val holdSpeed = floatPreferencesKey("hold_speed")
        val subtitleScale = floatPreferencesKey("subtitle_scale")
        val subtitleBackground = booleanPreferencesKey("subtitle_background")
        val subtitleBottom = floatPreferencesKey("subtitle_bottom")
        val brightnessGesture = booleanPreferencesKey("brightness_gesture")
        val volumeGesture = booleanPreferencesKey("volume_gesture")
        val swipeSeek = booleanPreferencesKey("swipe_seek")
        val directLinks = booleanPreferencesKey("direct_links")
        val resizeMode = intPreferencesKey("resize_mode")
        val pip = booleanPreferencesKey("pip")
        val recentSearches = stringPreferencesKey("recent_searches")
        val castId = stringPreferencesKey("cast_id")
        val castName = stringPreferencesKey("cast_name")
        val castReceiver = booleanPreferencesKey("cast_receiver")
        val playTarget = stringPreferencesKey("play_target")
    }

    /** This device on the casting socket: its id (made once), its name if the user set one, and whether it takes casts. */
    data class CastPrefs(val id: String, val name: String?, val receiver: Boolean, val playTarget: String?)

    suspend fun cast(): CastPrefs {
        var id: String? = null
        context.store.edit { p -> id = p[K.castId] ?: java.util.UUID.randomUUID().toString().also { p[K.castId] = it } }
        val p = context.store.data.first()
        return CastPrefs(id!!, p[K.castName], p[K.castReceiver] ?: true, p[K.playTarget])
    }

    suspend fun setCastName(name: String?) = context.store.edit { if (name == null) it.remove(K.castName) else it[K.castName] = name }
    suspend fun setCastReceiver(on: Boolean) = context.store.edit { it[K.castReceiver] = on }
    suspend fun setPlayTarget(target: String?) = context.store.edit { if (target == null) it.remove(K.playTarget) else it[K.playTarget] = target }

    suspend fun server(): ServerConfig? =
        context.store.data.first()[K.server]?.let { runCatching { json.decodeFromString<ServerConfig>(it) }.getOrNull() }

    suspend fun setServer(config: ServerConfig?) {
        context.store.edit { if (config == null) it.remove(K.server) else it[K.server] = json.encodeToString(config) }
    }

    val homeProvider: Flow<String?> = context.store.data.map { it[K.homeProvider] }
    suspend fun setHomeProvider(name: String) = context.store.edit { it[K.homeProvider] = name }
    // Preserve the old single-category preference until the user changes their selection.
    val homeCategories: Flow<Set<String>> = context.store.data.map {
        it[K.homeCategories] ?: setOfNotNull(it[K.homeType]?.takeUnless { id -> id == "all" })
    }
    suspend fun setHomeCategories(ids: Set<String>) = context.store.edit {
        it[K.homeCategories] = ids
        it.remove(K.homeType)
    }

    /** The last few searches, newest first. */
    val recentSearches: Flow<List<String>> = context.store.data.map { RecentSearches.decode(it[K.recentSearches]) }
    suspend fun addRecentSearch(query: String) = context.store.edit {
        it[K.recentSearches] = RecentSearches.encode(RecentSearches.add(RecentSearches.decode(it[K.recentSearches]), query))
    }
    suspend fun clearRecentSearches() = context.store.edit { it.remove(K.recentSearches) }

    val player: Flow<PlayerPrefs> = context.store.data.map { p -> p.toPlayerPrefs() }

    private fun Preferences.toPlayerPrefs(): PlayerPrefs {
        val d = PlayerPrefs()
        return PlayerPrefs(
            seekStep = this[K.seekStep] ?: d.seekStep,
            holdSpeed = this[K.holdSpeed] ?: d.holdSpeed,
            subtitleScale = this[K.subtitleScale] ?: d.subtitleScale,
            subtitleBackground = this[K.subtitleBackground] ?: d.subtitleBackground,
            subtitleBottom = this[K.subtitleBottom] ?: d.subtitleBottom,
            brightnessGesture = this[K.brightnessGesture] ?: d.brightnessGesture,
            volumeGesture = this[K.volumeGesture] ?: d.volumeGesture,
            swipeSeek = this[K.swipeSeek] ?: d.swipeSeek,
            directLinks = this[K.directLinks] ?: d.directLinks,
            resizeMode = this[K.resizeMode] ?: d.resizeMode,
            pictureInPicture = this[K.pip] ?: d.pictureInPicture,
        )
    }

    suspend fun updatePlayer(change: (PlayerPrefs) -> PlayerPrefs) {
        context.store.edit { p ->
            val n = change(p.toPlayerPrefs())
            p[K.seekStep] = n.seekStep
            p[K.holdSpeed] = n.holdSpeed
            p[K.subtitleScale] = n.subtitleScale
            p[K.subtitleBackground] = n.subtitleBackground
            p[K.subtitleBottom] = n.subtitleBottom
            p[K.brightnessGesture] = n.brightnessGesture
            p[K.volumeGesture] = n.volumeGesture
            p[K.swipeSeek] = n.swipeSeek
            p[K.directLinks] = n.directLinks
            p[K.resizeMode] = n.resizeMode
            p[K.pip] = n.pictureInPicture
        }
    }
}

/** Recent searches, kept newest first without repeats (ignoring case) and capped. */
object RecentSearches {
    const val MAX = 8

    fun add(list: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    fun encode(list: List<String>): String = list.joinToString("\n")
    fun decode(raw: String?): List<String> = raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
