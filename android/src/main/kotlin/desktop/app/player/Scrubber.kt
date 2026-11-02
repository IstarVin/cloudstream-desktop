@file:OptIn(UnstableApi::class)

package desktop.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/**
 * One scrub (a seekbar drag or a horizontal swipe) on [player], using Media3's scrubbing mode: audio is dropped and the
 * decoder isn't flushed between seeks, so a seek per touch event keeps the picture following the finger. Playback holds
 * while scrubbing and resumes from the exact release point.
 */
internal class Scrubber(private val player: ExoPlayer) {
    private var resume = false
    private var active = false

    /** Starts scrubbing; false when the current media can't be seeked (unknown duration, live). */
    fun begin(): Boolean {
        if (active) return true
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0 || !player.isCurrentMediaItemSeekable) return false
        resume = player.playWhenReady
        player.playWhenReady = false
        // Without a fractional tolerance, scrubbing mode uses these: snap to the nearest keyframe instead of decoding up to the target.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.setScrubbingModeEnabled(true)
        active = true
        return true
    }

    fun seek(positionMs: Long) {
        if (active) player.seekTo(positionMs)
    }

    /** Ends the scrub with an exact seek to [positionMs] and resumes playback if it was playing. */
    fun end(positionMs: Long) {
        if (!active) return
        active = false
        player.setScrubbingModeEnabled(false)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(positionMs)
        player.playWhenReady = resume
    }
}
