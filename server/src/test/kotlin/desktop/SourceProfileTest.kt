package desktop

import desktop.api.LinkDto
import desktop.config.Settings
import desktop.db.Database
import desktop.links.LinkRanking
import desktop.player.AutoPlay
import desktop.player.SourceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceProfileTest {
    @Serializable
    data class CaseLink(val id: String, val source: String, val quality: Int, val priority: Int)
    @Serializable
    data class Case(val name: String, val profile: SourceProfile, val links: List<CaseLink>, val expected: List<String>)
    @Serializable
    data class Cases(val cases: List<Case>)

    @Test
    fun rankingAndPrioritiesMatchTheWeb() {
        val cases = Json.decodeFromString<Cases>(File("../testdata/source-priority-cases.json").readText()).cases
        for (case in cases) {
            val links = case.links.mapIndexed { i, l ->
                assertEquals(l.priority, case.profile.priority(l.source, l.quality), case.name)
                LinkDto(l.id, l.source, l.id, l.quality, "", "M3U8", false, true, false, "", i)
            }
            assertEquals(case.expected, links.sortedWith(LinkRanking.comparator(case.profile)).map { it.id }, case.name)
            val unsupported = links.first().copy(id = "drm", playable = false)
            assertEquals("drm", (links + unsupported).sortedWith(LinkRanking.comparator(case.profile)).last().id)
        }
    }

    @Test
    fun filtersKeepCurrentLinksAndTreatZeroAsVisible() {
        val profile = SourceProfile(hideFailedSources = true, hideNegativeSources = true)
        assertFalse(profile.visible("bad", 10, setOf("bad"), null))
        assertFalse(profile.visible("negative", -1, emptySet(), null))
        assertTrue(profile.visible("zero", 0, emptySet(), null))
        assertTrue(profile.visible("current", -10, setOf("current"), "current"))
        assertTrue(SourceProfile().visible("bad", -10, setOf("bad"), null))
    }

    @Test
    fun autoPlayStartsAtPriorityTen() {
        fun ready(priority: Int) = AutoPlay.ready(fallback = false, done = false, bestPriority = priority)
        assertFalse(ready(9))
        assertTrue(ready(10))
        assertTrue(ready(11))
    }

    @Test
    fun settingsExposeDefaultsAndPersistIsolatedProfilesAndSelection() {
        val file = File(createTempDirectory().toFile(), "profiles.db")
        val settings = Settings(Database(file), emptyMap())
        val defaults = settings.all()[Settings.SOURCE_PROFILES]!!.jsonArray
        assertEquals(7, defaults.size)
        assertEquals(JsonPrimitive("Profile 1"), defaults[0].jsonObject["name"])
        assertEquals(JsonObject(emptyMap()), defaults[0].jsonObject["sourcePriorities"])
        assertEquals(JsonPrimitive(false), defaults[0].jsonObject["hideNegativeSources"])
        val profiles = SourceProfile.defaults().map { if (it.id == 2) it.copy(name = "Low data", sourcePriorities = mapOf("A" to 15), hideNegativeSources = true) else it }
        settings.update(JsonObject(mapOf(
            Settings.SOURCE_PROFILES to Json.encodeToJsonElement(profiles),
            Settings.SOURCE_PROFILE to JsonPrimitive(2),
        )))
        val reloaded = Settings(Database(file), emptyMap())
        assertEquals("Low data", reloaded.activeSourceProfile().name)
        assertEquals(20, reloaded.activeSourceProfile().priority("A", 720))
        assertEquals(6, reloaded.sourceProfiles().first().priority("A", 720))
    }

    @Test
    fun invalidPatchesCannotPartiallyChangeSelection() {
        val settings = Settings(Database(File(createTempDirectory().toFile(), "profiles.db")), emptyMap())
        val invalid = listOf(
            SourceProfile.defaults().dropLast(1),
            SourceProfile.defaults().map { it.copy(id = 1) },
            SourceProfile.defaults().map { it.copy(name = " ") },
            SourceProfile.defaults().map { it.copy(sourcePriorities = mapOf("A" to 1001)) },
            SourceProfile.defaults().map { it.copy(qualityPriorities = mapOf(999 to 2)) },
        )
        for (profiles in invalid) {
            assertFailsWith<IllegalArgumentException> {
                settings.update(JsonObject(mapOf(Settings.SOURCE_PROFILE to JsonPrimitive(2), Settings.SOURCE_PROFILES to Json.encodeToJsonElement(profiles))))
            }
            assertEquals(1, settings.activeSourceProfile().id)
        }
        for (id in listOf(JsonPrimitive(0), JsonPrimitive(8), JsonPrimitive(1.5), JsonPrimitive("2"))) {
            assertFailsWith<IllegalArgumentException> { settings.update(JsonObject(mapOf(Settings.SOURCE_PROFILE to id))) }
        }
    }
}
