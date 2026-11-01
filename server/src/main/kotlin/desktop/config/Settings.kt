package desktop.config

import desktop.db.Database
import desktop.player.SourceProfile
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** UI-editable settings: values saved from the Settings page (SQLite) over the defaults from config.toml. */
class Settings(private val db: Database, private val defaults: Map<String, Any>) {
    private val profileJson = Json { encodeDefaults = true }
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun onChange(listener: (key: String) -> Unit) {
        listeners.add(listener)
    }

    private fun stored(key: String): JsonElement? =
        db.query("SELECT value FROM settings WHERE key = ?", key) { it.getString(1) }.firstOrNull()?.let(Json::parseToJsonElement)

    fun bool(key: String): Boolean = (stored(key) as? JsonPrimitive)?.booleanOrNull ?: defaults[key] as Boolean
    fun long(key: String): Long = (stored(key) as? JsonPrimitive)?.longOrNull ?: (defaults[key] as Number).toLong()
    fun string(key: String): String = (stored(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: defaults[key].toString()

    @Suppress("UNCHECKED_CAST")
    fun strings(key: String): List<String> =
        (stored(key) as? JsonArray)?.map { (it as JsonPrimitive).content } ?: defaults[key] as List<String>

    val showNsfw get() = bool(SHOW_NSFW)
    val disabledPlugins get() = strings(DISABLED_PLUGINS).toSet()

    fun sourceProfiles(): List<SourceProfile> = stored(SOURCE_PROFILES)?.let {
        Json.decodeFromString<List<SourceProfile>>(it.toString())
    } ?: SourceProfile.defaults()

    fun activeSourceProfile(): SourceProfile {
        val id = (stored(SOURCE_PROFILE) as? JsonPrimitive)?.longOrNull?.toInt() ?: 1
        return sourceProfiles().first { it.id == id }
    }

    /** Every setting as JSON, for the Settings page. */
    fun all(): JsonObject = JsonObject(defaults.keys.associateWith { key ->
        when (val d = defaults[key]) {
            is Boolean -> JsonPrimitive(bool(key))
            is Number -> JsonPrimitive(long(key))
            is List<*> -> JsonArray(strings(key).map(::JsonPrimitive))
            else -> JsonPrimitive(string(key))
        }
    } + mapOf(
        SOURCE_PROFILE to JsonPrimitive(activeSourceProfile().id),
        SOURCE_PROFILES to profileJson.encodeToJsonElement(sourceProfiles()),
    ))

    /** Saves the known keys from [values], checking each against its default's type. */
    fun update(values: JsonObject) {
        // Validate the entire patch before writing: a malformed profile must not change the active selection.
        for ((key, v) in values) {
            if (key == SOURCE_PROFILE) {
                require(v is JsonPrimitive && !v.isString && v.longOrNull in 1L..SourceProfile.COUNT.toLong()) { "Unknown source profile" }
                continue
            }
            if (key == SOURCE_PROFILES) {
                val profiles = try {
                    Json.decodeFromString<List<SourceProfile>>(v.toString())
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid source profiles", e)
                }
                require(profiles.size == SourceProfile.COUNT && profiles.map { it.id }.toSet() == (1..SourceProfile.COUNT).toSet()) {
                    "Exactly seven unique source profiles are required"
                }
                profiles.forEach { it.validate() }
                continue
            }
            val d = defaults[key] ?: throw IllegalArgumentException("Unknown setting $key")
            val ok = when (d) {
                is Boolean -> (v as? JsonPrimitive)?.booleanOrNull != null
                is Number -> (v as? JsonPrimitive)?.longOrNull != null
                is List<*> -> v is JsonArray && v.all { it is JsonPrimitive && it.isString }
                else -> v is JsonPrimitive && v.isString && choices[key].let { it == null || v.content in it } &&
                    (key != PLUGIN_REPO_URL || v.content.matches(Regex("""https?://\S+""")))
            }
            require(ok) { "Setting $key has the wrong type or value" }
        }
        for ((key, v) in values) {
            db.update("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, v.toString())
            listeners.forEach { it(key) }
        }
    }

    companion object {
        const val MPV_EXTRA_ARGS = "mpv_extra_args"
        const val SOURCE_PROFILE = "source_profile"
        const val SOURCE_PROFILES = "source_profiles"
        const val AUTOPLAY_BEST = "autoplay_best"
        const val AUTOPLAY_MIN_LINKS = "autoplay_min_links"
        const val AUTOPLAY_WAIT_MS = "autoplay_wait_ms"
        const val AUTOPLAY_NEXT = "autoplay_next"
        const val SHOW_NSFW = "show_nsfw"
        const val DISABLED_PLUGINS = "disabled_plugins"
        const val PROXY_ALL = "proxy_all"
        /** prompt | auto | off: what to do inside an AniSkip opening, ending or recap. */
        const val SKIP_SEGMENTS = "skip_segments"
        const val CHROMIUM_MODE = "chromium_mode"
        const val PLUGIN_SOURCE = "plugin_source"
        const val PLUGIN_REPO_URL = "plugin_repo_url"

        /** The allowed values of string settings that have a fixed set. */
        val choices = mapOf(
            CHROMIUM_MODE to setOf("headless", "xvfb", "headed"),
            PLUGIN_SOURCE to setOf("dev", "repo"),
            SKIP_SEGMENTS to setOf("prompt", "auto", "off"),
        )

        /** Settings only the desktop (loopback) may change: they decide what code the server downloads and runs. */
        val desktopOnly = setOf(PLUGIN_SOURCE, PLUGIN_REPO_URL)
    }
}
