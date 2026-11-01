package desktop

import desktop.config.Toml
import kotlin.test.Test
import kotlin.test.assertEquals

class TomlTest {
    @Test
    fun readsTheConfigSubset() {
        val t = Toml.parse(
            """
            bind = "0.0.0.0:6969"            # LAN remote
            plugins.dev_path = "/mnt/x # not a comment"
            plugins.build_on_start = true
            mpv.extra_args = ["--profile=gpu-hq",
                "--fs"]
            playback.autoplay_wait_ms = 6_000

            [ui]
            show_nsfw = false
            """.trimIndent()
        )
        assertEquals("0.0.0.0:6969", t["bind"])
        assertEquals("/mnt/x # not a comment", t["plugins.dev_path"])
        assertEquals(true, t["plugins.build_on_start"])
        assertEquals(listOf("--profile=gpu-hq", "--fs"), t["mpv.extra_args"])
        assertEquals(6000L, t["playback.autoplay_wait_ms"])
        assertEquals(false, t["ui.show_nsfw"])
    }

    @Test
    fun readsTheShippedDefaultConfig() {
        val t = Toml.parse(javaClass.getResource("/default-config.toml")!!.readText())
        assertEquals(listOf("Fetcharr", "StreamCorner"), t["plugins.disabled"])
        assertEquals(emptyList<Any>(), t["mpv.extra_args"])
    }
}
