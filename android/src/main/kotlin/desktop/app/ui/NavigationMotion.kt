package desktop.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Uses Compose's animation clock, including Android's system animation duration scale. */
internal class NavigationMotion(private val forwardOffset: Int) {
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

    private companion object {
        const val TabDuration = 180
        const val SlideDuration = 280
        const val EnterDuration = 220
        const val ExitDuration = 140
    }
}
