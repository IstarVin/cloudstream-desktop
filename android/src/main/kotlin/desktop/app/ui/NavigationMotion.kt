package desktop.app.ui

import androidx.activity.BackEventCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Uses Compose's animation clock, including Android's system animation duration scale.
 *
 * Forward and button-back moves share a short axis slide. A back *gesture* follows Android's predictive-back design for
 * full-screen surfaces instead: the leaving screen shrinks toward the swipe edge and fades out, then the screen behind
 * fades in at full size (fade-through 35% of the way).
 *
 * [edgeMargin] is the gap, in px, the shrunken screen keeps from the swipe edge (8dp in the spec).
 */
internal class NavigationMotion(private val forwardOffset: Int, private val edgeMargin: Int = 0) {
    fun enter(detail: Boolean, back: Boolean = false): EnterTransition {
        if (!detail) return fadeIn(tween(TabDuration, easing = LinearOutSlowInEasing))

        // Start revealing immediately so navigation never pauses on an empty surface.
        val offset = if (back) -forwardOffset else forwardOffset
        return fadeIn(tween(EnterDuration, easing = LinearOutSlowInEasing)) +
            slideInHorizontally(tween(SlideDuration, easing = FastOutSlowInEasing)) { offset }
    }

    fun exit(detail: Boolean, back: Boolean = false): ExitTransition {
        if (!detail) return fadeOut(tween(TabDuration, easing = FastOutLinearInEasing))

        val offset = if (back) forwardOffset else -forwardOffset
        return fadeOut(tween(ExitDuration, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(tween(SlideDuration, easing = FastOutSlowInEasing)) { offset }
    }

    /** The screen behind a back gesture: held invisible until the leaving one has faded, then scales 110% -> 100%. */
    fun predictiveEnter(detail: Boolean): EnterTransition {
        if (!detail) return enter(detail = false)

        return fadeIn(tween(PredictiveDuration - FadeOutMs, delayMillis = FadeOutMs, easing = LinearEasing)) +
            scaleIn(tween(PredictiveDuration, easing = PredictiveEasing), initialScale = 1.1f)
    }

    /** The screen being dismissed: scales to 90%, shifted so it keeps [edgeMargin] from the swipe edge, and is gone 35% of the way through. */
    fun predictiveExit(detail: Boolean, swipeEdge: Int): ExitTransition {
        if (!detail) return exit(detail = false)

        // Scaling about the center leaves width/20 on each side; shift until only the margin remains on the swipe edge.
        val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) 1 else -1
        return fadeOut(tween(FadeOutMs, easing = LinearEasing)) +
            scaleOut(tween(PredictiveDuration, easing = PredictiveEasing), targetScale = 0.9f) +
            slideOutHorizontally(tween(PredictiveDuration, easing = PredictiveEasing)) { width ->
                direction * (width / 20 - edgeMargin).coerceAtLeast(0)
            }
    }

    private companion object {
        const val TabDuration = 180
        const val SlideDuration = 280
        const val EnterDuration = 220
        const val ExitDuration = 140

        const val PredictiveDuration = 300

        /** The leaving screen is fully faded 35% of the way through; the screen behind starts fading in there. */
        const val FadeOutMs = 105

        /** Android's decelerate for back previews: the gesture reads clearly at its start. */
        val PredictiveEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
    }
}
