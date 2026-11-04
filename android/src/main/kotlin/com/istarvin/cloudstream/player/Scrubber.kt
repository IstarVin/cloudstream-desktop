@file:OptIn(UnstableApi::class)

package com.istarvin.cloudstream.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters

/**
 * One scrub (a seekbar drag or a horizontal swipe) on [player], using Media3's scrubbing mode: the decoder isn't flushed
 * between seeks, so a seek per touch event keeps the picture following the finger. Seeks stay
 * frame-exact (the player's default) rather than snapping to keyframes, which would make the picture jump. Playback holds
 * while scrubbing and resumes from the exact release point.
 */
internal class Scrubber(private val player: ExoPlayer) {
    private var resume = false
    private var active = false
    private var lastSeek = C.TIME_UNSET

    /** Starts scrubbing; false when the current media can't be seeked (unknown duration, live). */
    fun begin(): Boolean {
        if (active) return true
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0 || !player.isCurrentMediaItemSeekable) return false
        resume = player.playWhenReady
        player.playWhenReady = false
        player.scrubbingModeParameters = KEEP_TRACKS
        player.isScrubbingModeEnabled = true
        lastSeek = C.TIME_UNSET
        active = true
        return true
    }

    fun seek(positionMs: Long) {
        // Touch events outnumber distinct targets; a repeat would only restart the same decode.
        if (!active || positionMs == lastSeek) return
        lastSeek = positionMs
        player.seekTo(positionMs)
    }

    /** Ends the scrub with an exact seek to [positionMs] and resumes playback if it was playing. */
    fun end(positionMs: Long) {
        if (!active) return
        active = false
        player.isScrubbingModeEnabled = false
        player.seekTo(positionMs)
        player.playWhenReady = resume
    }

    private companion object {
        // The default drops the audio track, and re-enabling it on release flushes the whole buffer. Playback is paused while scrubbing anyway.
        val KEEP_TRACKS = ScrubbingModeParameters.Builder().setDisabledTrackTypes(emptySet()).build()
    }
}
