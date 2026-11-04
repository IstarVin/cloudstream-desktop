@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package desktop.app.player

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Keeps captions in the visible viewport while the video is fitted, stretched or cropped. */
internal class ViewportPlayerView(context: Context) : PlayerView(context) {
    private val videoFrame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)

    init {
        // Keep the original SubtitleView so PlayerView still delivers cues to it and libass
        // can attach its overlay, but remove it from the frame that changes aspect ratio.
        subtitleView?.let { subtitles ->
            (subtitles.parent as ViewGroup).removeView(subtitles)
            addView(subtitles, indexOfChild(videoFrame) + 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setVideoScale(scale: Float) {
        videoFrame.scaleX = scale
        videoFrame.scaleY = scale
    }
}
