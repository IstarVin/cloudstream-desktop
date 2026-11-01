package desktop

import desktop.links.ClearKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClearKeyTest {
    @Test
    fun readsHexAndJwkKeys() {
        val hex = "76a6c65c5ea762046bd749a2e632ccbb"
        assertEquals(hex, ClearKey.toHex(hex.uppercase()))
        // StreamCorner's form: the 16 bytes as unpadded base64url.
        assertEquals(hex, ClearKey.toHex("dqbGXF6nYgRr10mi5jLMuw"))
        assertEquals(hex, ClearKey.toHex("dqbGXF6nYgRr10mi5jLMuw=="))
        assertNull(ClearKey.toHex("dqbGXF6nYgRr"), "not 16 bytes")
        assertNull(ClearKey.toHex("not a key!"))
    }
}
