package desktop

import desktop.config.Settings
import desktop.db.Database
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginSelectionTest {
    @Test
    fun explicitSelectionStartsEmptyAndSurvivesRestart() {
        val file = File(createTempDirectory().toFile(), "settings.db")
        val defaults = mapOf(Settings.ENABLED_PLUGINS to emptyList<String>())
        val settings = Settings(Database(file), defaults)
        assertEquals(emptySet(), settings.enabledPlugins)
        settings.update(JsonObject(mapOf(Settings.ENABLED_PLUGINS to JsonArray(listOf(JsonPrimitive("Example@repo"))))))
        assertEquals(setOf("Example@repo"), Settings(Database(file), defaults).enabledPlugins)
    }
}
