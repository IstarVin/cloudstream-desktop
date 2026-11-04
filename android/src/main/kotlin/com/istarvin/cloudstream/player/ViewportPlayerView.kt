@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.istarvin.cloudstream.player

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.view.children
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlin.math.roundToInt

/** Keeps captions in the visible viewport while the video is fitted, stretched or cropped. */
internal class ViewportPlayerView(context: Context) : PlayerView(context) {
    private val videoFrame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
    // SubtitleView creates its text renderer before libass attaches a separate overlay.
    private val textSubtitleOutput = subtitleView?.getChildAt(0)
    private var subtitleBottomFraction = 0f

    init {
        textSubtitleOutput?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePadding() }
        // Keep the original SubtitleView so PlayerView still delivers cues to it and libass
        // can attach its overlay, but remove it from the frame that changes aspect ratio.
        subtitleView?.let { subtitles ->
            (subtitles.parent as ViewGroup).removeView(subtitles)
            addView(subtitles, indexOfChild(videoFrame) + 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        videoFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) post { fitAssOverlay() }
        }
    }

    /**
     * libass lays its frame out over the video surface (ExoPlayer reports the surface size, which is the fitted video
     * rectangle), but the overlay is added to the full-viewport [subtitleView] and would draw that frame at its
     * top-left. When the window isn't the video's aspect ratio the two differ and ASS text lands offset, so the
     * overlay takes the video frame's size and sits at its centre.
     */
    private fun fitAssOverlay() {
        val overlay = subtitleView?.children?.firstOrNull { it is AssSubtitleView } ?: return
        val w = videoFrame.width
        val h = videoFrame.height
        if (w <= 0 || h <= 0) return
        val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.CENTER
        overlay.layoutParams = lp
    }

    /** Applies the safe area to explicit VTT line positions as well as unpositioned text cues. */
    fun setSubtitleBottomPaddingFraction(fraction: Float) {
        subtitleBottomFraction = fraction
        // Media3's fractional margin only applies to cues with no line position. VTT's
        // automatic lines are already numbered (-1, -2, ...), so pad the text canvas instead.
        subtitleView?.setBottomPaddingFraction(0f)
        updateSubtitlePadding()
    }

    private fun updateSubtitlePadding() {
        textSubtitleOutput?.let { output ->
            output.setPadding(0, 0, 0, (output.height * subtitleBottomFraction).roundToInt())
        }
    }

    fun setVideoScale(scale: Float) {
        videoFrame.scaleX = scale
        videoFrame.scaleY = scale
    }
}
