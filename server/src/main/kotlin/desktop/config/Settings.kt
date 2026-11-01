package desktop.config

import desktop.db.Database
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

    /** Every setting as JSON, for the Settings page. */
    fun all(): JsonObject = JsonObject(defaults.keys.associateWith { key ->
        when (val d = defaults[key]) {
            is Boolean -> JsonPrimitive(bool(key))
            is Number -> JsonPrimitive(long(key))
            is List<*> -> JsonArray(strings(key).map(::JsonPrimitive))
            else -> JsonPrimitive(string(key))
        }
    })

    /** Saves the known keys from [values], checking each against its default's type. */
    fun update(values: JsonObject) {
        for ((key, v) in values) {
            val d = defaults[key] ?: throw IllegalArgumentException("Unknown setting $key")
            val ok = when (d) {
                is Boolean -> (v as? JsonPrimitive)?.booleanOrNull != null
                is Number -> (v as? JsonPrimitive)?.longOrNull != null
                is List<*> -> v is JsonArray && v.all { it is JsonPrimitive && it.isString }
                else -> v is JsonPrimitive && v.isString && choices[key].let { it == null || v.content in it }
            }
            require(ok) { "Setting $key has the wrong type or value" }
            db.update("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, v.toString())
            listeners.forEach { it(key) }
        }
    }

    companion object {
        const val MPV_EXTRA_ARGS = "mpv_extra_args"
        const val AUTOPLAY_BEST = "autoplay_best"
        const val AUTOPLAY_MIN_LINKS = "autoplay_min_links"
        const val AUTOPLAY_WAIT_MS = "autoplay_wait_ms"
        const val AUTOPLAY_NEXT = "autoplay_next"
        const val SHOW_NSFW = "show_nsfw"
        const val DISABLED_PLUGINS = "disabled_plugins"
        const val PROXY_ALL = "proxy_all"
        const val CHROMIUM_MODE = "chromium_mode"

        /** The allowed values of string settings that have a fixed set. */
        val choices = mapOf(CHROMIUM_MODE to setOf("headless", "xvfb", "headed"))
    }
}
