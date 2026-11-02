package desktop.app.ui

import desktop.api.PluginDto
import desktop.app.data.ApiException
import desktop.app.data.RecentSearches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UiHelpersTest {
    @Test fun remainingTimeIsRoundedUpAndSwitchesToHours() {
        assertEquals("1 min left", remainingLabel(0.0, 30.0))
        assertEquals("18 min left", remainingLabel(1620.0, 2700.0))
        assertEquals("1 h 30 min left", remainingLabel(0.0, 5400.0))
    }

    @Test fun remainingTimeIsAbsentWithoutADurationOrWhenFinished() {
        assertNull(remainingLabel(10.0, 0.0))
        assertNull(remainingLabel(100.0, 100.0))
        assertNull(remainingLabel(-1.0, 100.0))
    }

    @Test fun networkFailuresAreExplainedInsteadOfShowingSocketText() {
        assertEquals(UNREACHABLE, ConnectException("Failed to connect to /192.168.1.5:6969").userMessage())
        assertEquals(UNREACHABLE, UnknownHostException("desktop").userMessage())
        assertEquals(UNREACHABLE, IOException("unexpected end of stream on http://x/...").userMessage())
        assertTrue(SocketTimeoutException("timeout").userMessage().contains("too long"))
    }

    @Test fun serverAnswersKeepTheirOwnMessage() {
        assertEquals("No such title", ApiException(404, "No such title").userMessage())
        assertEquals("boom", IllegalStateException("boom").userMessage())
    }

    @Test fun recentSearchesKeepNewestFirstWithoutRepeatsAndCapAtEight() {
        var list = emptyList<String>()
        for (q in listOf("dune", "bear", "Dune ", "arcane")) list = RecentSearches.add(list, q)
        assertEquals(listOf("arcane", "Dune", "bear"), list)
        for (i in 1..10) list = RecentSearches.add(list, "q$i")
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("q10", list.first())
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.add(emptyList(), "   "))
    }

    private fun plugin(name: String, version: Int = 1, available: Int? = null, status: String = "loaded", error: String? = null) =
        PluginDto(name, version, null, status, error, false, emptyList(), true, listOf(name), 0, emptyList(), available = available)

    private val plugins = listOf(
        plugin("Zeta"), plugin("Alpha", version = 2, available = 3), plugin("Broken", status = "error", error = "boom"), plugin("Same", version = 4, available = 4),
    )

    @Test fun pluginFiltersSortByNameAndNarrowByQueryAndChip() {
        assertEquals(listOf("Alpha", "Broken", "Same", "Zeta"), filterPlugins(plugins, "", PluginFilter.All).map { it.name })
        assertEquals(listOf("Alpha"), filterPlugins(plugins, "", PluginFilter.Updates).map { it.name })
        assertEquals(listOf("Broken"), filterPlugins(plugins, "", PluginFilter.Issues).map { it.name })
        assertEquals(listOf("Zeta"), filterPlugins(plugins, " zet", PluginFilter.All).map { it.name })
    }

    @Test fun anAvailableVersionOnlyCountsWhenItIsNewer() {
        assertTrue(plugin("a", version = 1, available = 2).hasUpdate())
        assertTrue(!plugin("a", version = 2, available = 2).hasUpdate())
        assertTrue(!plugin("a", version = 2, available = null).hasUpdate())
        assertTrue(plugin("a", status = "ERROR").hasIssue())
    }
}
