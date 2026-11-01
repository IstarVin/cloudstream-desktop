package desktop.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

/** One labelled setting a plugin declares, rendered by the web UI's plugin settings form. */
@Serializable
data class SettingField(
    /** The preference file; null means the plugin's first one. */
    val file: String? = null,
    val key: String,
    /** string | secret | boolean | int | choice */
    val type: String = "string",
    val label: String,
    val help: String? = null,
    val default: String? = null,
    val options: List<SettingOption> = emptyList(),
)

@Serializable
data class SettingOption(val value: String, val label: String)

/**
 * Reads a plugin's settings schema: a `const val SETTINGS_SCHEMA` JSON string on its `Plugin` class (a public static
 * field once compiled). It's a string rather than a shared type so the Cloudstream library doesn't change, and a
 * static field survives dex2jar, so published jars and converted `.cs3`s both carry it. Android ignores it.
 *
 * Format: `[{"file": "…", "key": "…", "type": "string|secret|boolean|int|choice", "label": "…", "help": "…",
 * "default": …, "options": [{"value": "…", "label": "…"}]}]`.
 */
object SettingsSchema {
    private val log = LoggerFactory.getLogger(SettingsSchema::class.java)
    private val types = setOf("string", "secret", "boolean", "int", "choice")

    fun read(cls: Class<*>): List<SettingField> {
        val field = runCatching { cls.getField(FIELD) }.getOrNull()?.takeIf { Modifier.isStatic(it.modifiers) } ?: return emptyList()
        val text = runCatching { field.get(null) as? String }.getOrNull() ?: return emptyList()
        return try {
            parse(text)
        } catch (e: Exception) {
            log.warn("{}.{} isn't a valid settings schema: {}", cls.name, FIELD, e.message)
            emptyList()
        }
    }

    fun parse(text: String): List<SettingField> = (Json.parseToJsonElement(text) as JsonArray).map { el ->
        val o = el.jsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val key = requireNotNull(str("key")) { "a setting has no key" }
        val type = str("type") ?: "string"
        require(type in types) { "$key has unknown type $type" }
        SettingField(
            file = str("file"),
            key = key,
            type = type,
            label = str("label") ?: key,
            help = str("help"),
            default = str("default"),
            options = (o["options"] as? JsonArray).orEmpty().map { opt ->
                when (opt) {
                    is JsonObject -> {
                        val v = requireNotNull(opt["value"]?.jsonPrimitive?.contentOrNull) { "$key has an option without a value" }
                        SettingOption(v, opt["label"]?.jsonPrimitive?.contentOrNull ?: v)
                    }
                    else -> opt.jsonPrimitive.content.let { SettingOption(it, it) }
                }
            },
        )
    }

    const val FIELD = "SETTINGS_SCHEMA"
}
