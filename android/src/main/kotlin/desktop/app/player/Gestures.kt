package desktop.app.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the player does with each gesture; [PlayerScreen] implements it. */
interface GestureHandler {
    /** A single tap (shown after the double-tap window, so it doesn't fire under a double tap). */
    fun onTap()
    /** Each tap of a double (or longer) tap on the left (-1) or right (1) side. */
    fun onMultiTap(side: Int)
    fun onHoldStart()
    fun onHoldEnd()
    fun onSeekStart()
    /** Horizontal travel since the drag started, as a fraction of the width (negative is left). */
    fun onSeek(fraction: Float)
    fun onSeekEnd()
    fun onVerticalStart(left: Boolean)
    /** Upward travel since the last call, as a fraction of the height. */
    fun onVertical(delta: Float)
    fun onVerticalEnd()
    /** Two-finger zoom: the spread relative to where it started; [done] on release. */
    fun onPinch(scale: Float, done: Boolean)
}

private enum class Mode { Hold, Seek, Vertical, Pinch, Ignore }

/**
 * The player's touch surface. One gesture at a time: a tap or multi-tap, a long press (held for speed), a horizontal
 * drag (scrub seek), a vertical drag (brightness on the left, volume on the right) or a pinch. Vertical drags that
 * start at the top or bottom edge are left to the system, where they bring back its bars.
 *
 * [locked] keeps only single taps, so the lock button can be shown again.
 */
fun Modifier.playerGestures(handler: GestureHandler, locked: Boolean, swipeSeek: Boolean, vertical: (left: Boolean) -> Boolean): Modifier =
    pointerInput(handler, locked, swipeSeek) {
        val edge = 48.dp.toPx()
        val doubleTap = viewConfiguration.doubleTapTimeoutMillis
        var lastTapAt = 0L
        var multiTapping = false
        var pendingTap: Job? = null

        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val side = when {
                    start.x < w * 0.38f -> -1
                    start.x > w * 0.62f -> 1
                    else -> 0
                }
                var up: PointerInputChange? = null
                var mode: Mode? = null

                // Until the long-press timeout: a release is a tap, movement picks a drag, a second finger a pinch.
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } > 1) {
                            mode = Mode.Pinch
                            return@withTimeoutOrNull
                        }
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                        if (!c.pressed) {
                            up = c
                            return@withTimeoutOrNull
                        }
                        val d = c.position - start
                        if (d.getDistance() > viewConfiguration.touchSlop) {
                            mode = when {
                                locked -> Mode.Ignore
                                abs(d.x) > abs(d.y) -> if (swipeSeek) Mode.Seek else Mode.Ignore
                                start.y < edge || start.y > h - edge -> Mode.Ignore
                                vertical(start.x < w / 2) -> Mode.Vertical
                                else -> Mode.Ignore
                            }
                            return@withTimeoutOrNull
                        }
                    }
                }

                val released = up
                if (released != null) {
                    val now = released.uptimeMillis
                    val quick = now - lastTapAt < doubleTap
                    lastTapAt = now
                    if (!locked && side != 0 && (quick || multiTapping)) {
                        pendingTap?.cancel()
                        multiTapping = true
                        handler.onMultiTap(side)
                        pendingTap = launch {
                            delay(doubleTap)
                            multiTapping = false
                        }
                    } else if (side == 0 || locked) {
                        multiTapping = false
                        handler.onTap()
                    } else {
                        // Wait out the double-tap window before treating it as a single tap.
                        pendingTap?.cancel()
                        pendingTap = launch {
                            delay(doubleTap)
                            handler.onTap()
                        }
                    }
                    return@awaitEachGesture
                }

                when (mode ?: if (locked) Mode.Ignore else Mode.Hold) {
                    Mode.Hold -> {
                        handler.onHoldStart()
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        handler.onHoldEnd()
                    }
                    Mode.Seek -> {
                        handler.onSeekStart()
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c != null) {
                                handler.onSeek((c.position.x - start.x) / w)
                                c.consume()
                            }
                        } while (ev.changes.any { it.pressed })
                        handler.onSeekEnd()
                    }
                    Mode.Vertical -> {
                        handler.onVerticalStart(start.x < w / 2)
                        var last = start.y
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c != null) {
                                handler.onVertical((last - c.position.y) / (h * 0.8f))
                                last = c.position.y
                                c.consume()
                            }
                        } while (ev.changes.any { it.pressed })
                        handler.onVerticalEnd()
                    }
                    Mode.Pinch -> {
                        var initial = 0f
                        var scale = 1f
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val dist = spread(pressed[0].position, pressed[1].position)
                                if (initial == 0f) initial = dist
                                scale = dist / initial
                                if (!locked) handler.onPinch(scale, done = false)
                            }
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        if (!locked) handler.onPinch(scale, done = true)
                    }
                    Mode.Ignore -> {}
                }
            }
        }
    }

private fun spread(a: Offset, b: Offset) = (a - b).getDistance().coerceAtLeast(1f)
