package desktop

import desktop.player.AutoPlay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPlayTest {
    private fun ready(candidates: Int, firstLinkAt: Long?, now: Long, done: Boolean = false, fallback: Boolean = false) =
        AutoPlay.ready(fallback, done, candidates, minLinks = 3, firstLinkAt = firstLinkAt, waitMs = 6000, now = now)

    @Test
    fun waitsForThreeLinksOrSixSecondsAfterTheFirst() {
        assertFalse(ready(0, null, 50_000), "nothing yet")
        assertFalse(ready(2, 10_000, 15_999))
        assertTrue(ready(2, 10_000, 16_000), "6 s after the first link")
        assertTrue(ready(3, 10_000, 10_500), "three links")
    }

    @Test
    fun goesAtOnceWhenTheProviderIsDoneOrOnFallback() {
        assertTrue(ready(1, 10_000, 10_100, done = true))
        assertTrue(ready(1, 10_000, 10_100, fallback = true))
    }
}
