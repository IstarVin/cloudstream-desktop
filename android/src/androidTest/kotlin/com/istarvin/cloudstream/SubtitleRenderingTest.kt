@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.istarvin.cloudstream

import android.net.Uri
import android.graphics.Color
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.lifecycle.ViewModelProvider
import com.istarvin.cloudstream.data.ServerConfig
import com.istarvin.cloudstream.player.PlayerViewModel
import desktop.api.SubtitleDto
import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.istarvin.cloudstream.player.ViewportPlayerView
import desktop.api.StreamDto
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.test.platform.app.InstrumentationRegistry
import com.istarvin.cloudstream.player.Engine
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRenderingTest {
    @Test fun failedDownloadCanBeRetriedWithoutStoppingVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = (instrumentation.targetContext.applicationContext as android.app.Application).app.api
        val originalServer = api.server
        val video = File(instrumentation.targetContext.cacheDir, "subtitle-retry-background.mp4")
        instrumentation.context.assets.open("subtitle-background.mp4").use { input -> video.outputStream().use(input::copyTo) }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/x-subrip")
                .body("1\n00:00:00,000 --> 00:00:12,000\nVisible subtitle\n").build())
            api.server = ServerConfig(server.url("/").toString().trimEnd('/'), "test")
            try {
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    lateinit var vm: PlayerViewModel
                    scenario.onActivity { activity ->
                        vm = ViewModelProvider(activity)[PlayerViewModel::class.java]
                        vm.player.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
                        vm.player.prepare()
                    }
                    fun await(description: String, check: () -> Boolean) {
                        val deadline = System.currentTimeMillis() + 5_000
                        while (System.currentTimeMillis() < deadline) {
                            var passed = false
                            instrumentation.runOnMainSync { passed = check() }
                            if (passed) return
                            Thread.sleep(50)
                        }
                        throw AssertionError(description)
                    }
                    await("Player ready") { vm.player.playbackState == Player.STATE_READY }
                    val subtitle = SubtitleDto("retry", "English", "https://example.invalid/sub.srt", "srt")
                    scenario.onActivity { vm.player.seekTo(5_000); vm.selectSubtitle(subtitle) }
                    await("Failed subtitle must show a retryable error") { vm.session.value.subtitleError != null }
                    scenario.onActivity {
                        assertNull(vm.session.value.subtitle)
                        assertNull(vm.player.playerError)
                        vm.selectSubtitle(subtitle)
                    }
                    await("Retry must select the downloaded track") { vm.session.value.subtitle == subtitle.id }
                    await("Retried player ready") { vm.player.playbackState == Player.STATE_READY }
                    scenario.onActivity {
                        assertNull(vm.session.value.subtitleError)
                        assertEquals(5_000L, vm.player.currentPosition)
                        assertTrue(vm.player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.any { it.id == "cs-sub-retry" })
                    }
                    assertEquals(2, server.requestCount)
                }
            } finally {
                api.server = originalServer
            }
        }
    }

    @Test fun srtVttAndAssAppearOverVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "subtitle-background.mp4")
        instrumentation.context.assets.open("subtitle-background.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val samples = listOf(
            "srt" to "1\n00:00:00,000 --> 00:00:12,000\nVisible subtitle\n",
            "vtt" to "WEBVTT\n\n00:00:00.000 --> 00:00:12.000\nVisible subtitle\n",
            "ass" to """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 640
                PlayResY: 360
                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Arial,30,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,20,20,20,1
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.00,0:00:12.00,Default,,0,0,0,,Visible subtitle
            """.trimIndent(),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for ((extension, text) in samples) {
                val subtitle = File(context.cacheDir, "visible.$extension").apply { writeText(text) }
                val ready = AtomicBoolean()
                lateinit var engine: Engine
                scenario.onActivity { activity ->
                    engine = Engine(activity, OkHttpClient())
                    val view = ViewportPlayerView(activity)
                    activity.setContentView(view, ViewGroup.LayoutParams(-1, -1))
                    view.player = engine.player
                    engine.attach(requireNotNull(view.subtitleView))
                    engine.player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { ready.set(true) }
                    })
                    val mime = when (extension) { "ass" -> MimeTypes.TEXT_SSA; "srt" -> MimeTypes.APPLICATION_SUBRIP; else -> MimeTypes.TEXT_VTT }
                    val config = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle))
                        .setId("caption").setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                    engine.player.setMediaItem(engine.item(StreamDto(video.toURI().toString(), "VIDEO", proxied = false), Uri.fromFile(video).toString(), listOf(config), "Test"))
                    engine.player.prepare()
                    engine.player.play()
                }
                try {
                    val deadline = System.currentTimeMillis() + 8_000
                    var visible = false
                    while (System.currentTimeMillis() < deadline && !visible) {
                        if (ready.get()) {
                            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                            var whitePixels = 0
                            // Black test video: a white caption must appear below the centre.
                            for (y in bitmap.height / 2 until bitmap.height * 95 / 100 step 2) {
                                for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 2) {
                                    val pixel = bitmap.getPixel(x, y)
                                    if (Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200) whitePixels++
                                }
                            }
                            bitmap.recycle()
                            visible = whitePixels > 50
                        }
                        if (!visible) Thread.sleep(100)
                    }
                    assertTrue("$extension caption must appear over the video", visible)
                } finally {
                    scenario.onActivity { engine.release() }
                }
            }
        }
    }

}
