package desktop.plugins

import kotlinx.serialization.Serializable

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
