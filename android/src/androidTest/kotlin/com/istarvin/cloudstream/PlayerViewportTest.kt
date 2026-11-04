@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.istarvin.cloudstream

import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.istarvin.cloudstream.player.ViewportPlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerViewportTest {
    @Test fun bottomMarginMovesVttAndUnpositionedCuesWithoutResizingText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = ViewportPlayerView(instrumentation.targetContext)
            val subtitles = requireNotNull(view.subtitleView)
            subtitles.setFractionalTextSize(0.0533f, true)

            for ((width, height) in listOf(640 to 360, 360 to 640)) {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
                // WebVTT assigns negative line numbers even when the file omits a position.
                for (line in listOf(Cue.DIMEN_UNSET, -1f, -2f)) {
                    val cue = Cue.Builder().setText("Visible caption")
                    if (line != Cue.DIMEN_UNSET) cue.setLine(line, Cue.LINE_TYPE_NUMBER)
                    subtitles.setCues(listOf(cue.build()))
                    fun renderedRows(): List<Int> {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        subtitles.draw(Canvas(bitmap))
                        val rows = (0 until height).filter { y ->
                            (0 until width).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
                        }
                        bitmap.recycle()
                        assertTrue("Caption must render", rows.isNotEmpty())
                        return rows
                    }
                    view.setSubtitleBottomPaddingFraction(0f)
                    val baseline = renderedRows()
                    for (fraction in listOf(0.06f, 0.2f, 0.06f)) {
                        view.setSubtitleBottomPaddingFraction(fraction)
                        val padded = renderedRows()
                        // Rasterization can add one antialiased row when the origin moves.
                        assertEquals(baseline.size.toFloat(), padded.size.toFloat(), 1f)
                        assertEquals(height * fraction, (baseline.last() - padded.last()).toFloat(), 1f)
                        assertTrue("Bottom pixel ${padded.last()}, height $height, margin $fraction, line $line", padded.last() <= height * (1f - fraction) + 1f)
                    }
                }
            }
        }
    }

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
