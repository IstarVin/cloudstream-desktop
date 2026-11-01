package desktop

import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import desktop.api.Mapper
import desktop.plugins.PluginRuntime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncIdTest {
    @Test
    fun malAndAniListIdsDontOverwriteEachOther() = runBlocking {
        // On the JVM the library's id keys all default to "", so the second id would replace the first.
        PluginRuntime.setSyncIdPrefixes()
        val api = object : MainAPI() { override var name = "Test" }
        val r = api.newAnimeLoadResponse("Frieren", "https://anilist.co/anime/154587", TvType.Anime) {
            addAniListId(154587)
            addMalId(52991)
        }
        val d = Mapper.details("Test", r, providerNsfw = false, showNsfw = false)
        assertEquals(52991, d.malId)
        assertEquals(154587, d.aniListId)
    }
}
