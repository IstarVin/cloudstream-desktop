package desktop

import desktop.api.showNsfw
import kotlin.test.Test
import kotlin.test.assertEquals

class ShowNsfwTest {
    @Test
    fun `the client's own choice wins over the server default`() {
        assertEquals(true, showNsfw("1", null, default = false))
        assertEquals(false, showNsfw("0", null, default = true))
    }

    @Test
    fun `EventSource requests choose with the query parameter`() {
        assertEquals(true, showNsfw(null, "1", default = false))
        assertEquals(false, showNsfw(null, "0", default = true))
    }

    @Test
    fun `a client that hasn't chosen gets the server default`() {
        assertEquals(true, showNsfw(null, null, default = true))
        assertEquals(false, showNsfw(null, null, default = false))
        assertEquals(false, showNsfw("yes", null, default = false), "anything else counts as no choice")
    }
}
