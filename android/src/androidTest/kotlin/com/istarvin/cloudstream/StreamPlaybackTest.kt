@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.istarvin.cloudstream

import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.istarvin.cloudstream.player.Engine
import desktop.api.StreamDto
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real-stream check: pass streamUrl and optionally seekMs as instrumentation arguments. */
class StreamPlaybackTest {
    @Test fun rendersVideoBeforeAndAfterSeek() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("streamUrl")
        assumeNotNull(url)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var engine: Engine
            val frames = AtomicInteger()
            scenario.onActivity { activity ->
                engine = Engine(activity, OkHttpClient())
                activity.setContentView(PlayerView(activity).apply { player = engine.player })
                engine.player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                })
                engine.player.setMediaItem(engine.item(StreamDto(url!!, "M3U8", proxied = true), url, emptyList(), "Stream check"))
                engine.player.prepare()
                engine.player.play()
            }
            fun awaitFrame(previous: Int) {
                val deadline = System.currentTimeMillis() + 60_000
                while (frames.get() <= previous && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { engine.player.playerError?.let { throw AssertionError("Playback failed", it) } }
                    Thread.sleep(100)
                }
                assertTrue("Video must render a frame", frames.get() > previous)
            }
            try {
                awaitFrame(0)
                val before = frames.get()
                scenario.onActivity { engine.player.seekTo(args.getString("seekMs")?.toLong() ?: 300_000L) }
                awaitFrame(before)
                scenario.onActivity { assertTrue("Video has dimensions", engine.player.videoSize.width > 0) }
            } finally {
                scenario.onActivity { engine.release() }
            }
        }
    }
}
