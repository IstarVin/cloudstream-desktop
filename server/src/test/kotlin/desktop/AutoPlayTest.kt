package desktop

import desktop.player.AutoPlay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPlayTest {
    private fun ready(bestPriority: Int, done: Boolean = false, fallback: Boolean = false) =
        AutoPlay.ready(fallback, done, bestPriority)

    @Test
    fun keepsWaitingUntilALinkReachesPriorityTen() {
        assertFalse(ready(0), "nothing yet")
        assertFalse(ready(9), "no matter how many low-priority links are in")
        assertTrue(ready(10))
    }

    @Test
    fun takesTheBestOnceEveryLinkIsInOrOnFallback() {
        assertTrue(ready(3, done = true))
        assertTrue(ready(3, fallback = true))
    }
}
