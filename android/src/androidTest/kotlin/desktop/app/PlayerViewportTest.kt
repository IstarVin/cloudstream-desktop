@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package desktop.app

import android.graphics.RectF
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import desktop.app.player.ViewportPlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerViewportTest {
    @Test fun captionsStayInViewportAcrossAspectRatiosResizeModesAndPinchScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = ViewportPlayerView(instrumentation.targetContext)
            val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val subtitles = requireNotNull(view.subtitleView)
            subtitles.setCues(listOf(Cue.Builder().setText("Visible caption").build()))

            for ((width, height) in listOf(1920 to 1080, 2400 to 1080, 1080 to 1920, 480 to 270)) {
                for (aspectRatio in listOf(4f / 3f, 16f / 9f, 2.39f)) {
                    for (mode in listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)) {
                        for (scale in listOf(0.7f, 1f, 2f)) {
                            view.resizeMode = mode
                            frame.setAspectRatio(aspectRatio)
                            view.setVideoScale(scale)
                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                            )
                            view.layout(0, 0, width, height)

                            val bounds = RectF(0f, 0f, subtitles.width.toFloat(), subtitles.height.toFloat())
                            subtitles.matrix.mapRect(bounds)
                            bounds.offset(subtitles.left.toFloat(), subtitles.top.toFloat())
                            assertEquals(RectF(0f, 0f, width.toFloat(), height.toFloat()), bounds)
                            assertTrue(subtitles.parent === view)
                            assertTrue(view.indexOfChild(subtitles) > view.indexOfChild(frame))
                            assertEquals(scale, frame.scaleX)
                            assertEquals(scale, frame.scaleY)
                        }
                    }
                }
            }
        }
    }
}
