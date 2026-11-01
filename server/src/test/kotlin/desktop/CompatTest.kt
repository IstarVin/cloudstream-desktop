package desktop

import android.util.Base64
import desktop.compat.JsonSharedPreferences
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CompatTest {
    @Test
    fun preferencesKeepTheirTypesAcrossReloads() {
        val file = File(createTempDirectory().toFile(), "Plugin.json")
        val prefs = JsonSharedPreferences(file)
        prefs.edit().putString("url", "https://x").putInt("n", 3).putLong("big", 1L shl 40)
            .putFloat("f", 1.5f).putBoolean("b", true).putStringSet("set", setOf("a", "b")).apply()
        assertEquals("fallback", prefs.getString("missing", "fallback"))

        val reloaded = JsonSharedPreferences(file)
        assertEquals("https://x", reloaded.getString("url", null))
        assertEquals(3, reloaded.getInt("n", 0))
        assertEquals(1L shl 40, reloaded.getLong("big", 0))
        assertEquals(1.5f, reloaded.getFloat("f", 0f))
        assertEquals(true, reloaded.getBoolean("b", false))
        assertEquals(setOf("a", "b"), reloaded.getStringSet("set", null))
        assertEquals("fallback", prefs.requestedKeys["missing"])
    }

    @Test
    fun base64FollowsAndroidFlags() {
        val bytes = ByteArray(60) { it.toByte() }
        val wrapped = Base64.encodeToString(bytes, Base64.DEFAULT)
        assertEquals('\n', wrapped.last())
        val urlSafe = Base64.encodeToString(byteArrayOf(-5, -1), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        assertEquals("-_8", urlSafe)
        assertContentEquals(bytes, Base64.decode(wrapped, Base64.DEFAULT))
        assertContentEquals(byteArrayOf(-5, -1), Base64.decode("-_8", Base64.URL_SAFE))
    }
}
