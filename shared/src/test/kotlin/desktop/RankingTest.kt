package desktop

import desktop.api.LinkDto
import desktop.links.LinkRanking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RankingTest {
    @Serializable
    data class Case(val name: String, val links: List<CaseLink>, val expected: List<String>)

    @Serializable
    data class CaseLink(val id: String, val quality: Int, val score: Double, val order: Int, val playable: Boolean)

    @Serializable
    data class Cases(val cases: List<Case>)

    @Test
    fun ranksLikeTheSharedCases() {
        // The web UI's tests read the same file (web/src/lib/ranking.test.ts).
        val file = File("../testdata/ranking-cases.json")
        val cases = Json { ignoreUnknownKeys = true }.decodeFromString<Cases>(file.readText()).cases
        for (c in cases) {
            val dtos = c.links.map {
                LinkDto(it.id, "S", it.id, it.quality, "", "M3U8", false, it.playable, false, "", it.order, it.score)
            }
            assertEquals(c.expected, dtos.sortedWith(LinkRanking.comparator).map { it.id }, c.name)
        }
    }
}
