package desktop

import desktop.plugins.SettingOption
import desktop.plugins.SettingsSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stands in for a plugin class with a declared schema, the way the extensions repo writes one. */
class SchemaPlugin {
    companion object {
        const val SETTINGS_SCHEMA = """[
            {"key": "api_key", "type": "secret", "label": "API key", "help": "From the site's account page"},
            {"file": "Other", "key": "server", "type": "choice", "label": "Server", "default": "a",
             "options": [{"value": "a", "label": "Server A"}, "b"]},
            {"key": "hd", "type": "boolean", "label": "Prefer HD", "default": true}
        ]"""
    }
}

class NoSchemaPlugin

class BrokenSchemaPlugin {
    companion object {
        const val SETTINGS_SCHEMA = """[{"key": "x", "type": "colour", "label": "X"}]"""
    }
}

class SettingsSchemaTest {
    @Test
    fun readsTheConstantOffThePluginClass() {
        val fields = SettingsSchema.read(SchemaPlugin::class.java)
        assertEquals(listOf("api_key", "server", "hd"), fields.map { it.key })
        assertEquals("secret", fields[0].type)
        assertEquals(null, fields[0].file, "no file: the host fills in the plugin's first one")
        assertEquals("Other", fields[1].file)
        assertEquals(listOf(SettingOption("a", "Server A"), SettingOption("b", "b")), fields[1].options)
        assertEquals("true", fields[2].default, "non-string defaults become strings")
    }

    @Test
    fun ignoresMissingOrInvalidSchemas() {
        assertTrue(SettingsSchema.read(NoSchemaPlugin::class.java).isEmpty())
        assertTrue(SettingsSchema.read(BrokenSchemaPlugin::class.java).isEmpty(), "unknown type: logged, not fatal")
    }
}
