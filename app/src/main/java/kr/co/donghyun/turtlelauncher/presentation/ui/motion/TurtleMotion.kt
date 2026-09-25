package kr.co.donghyun.turtlelauncher.presentation.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween

/**
 * Compose equivalents of Turtle's AnimPlayer/TurtleTransitions contract.
 *
 * The motion is centralized so migrated screens do not invent different
 * durations or easing curves. Callers may skip these transitions when the
 * user disables animation or when the game is active.
 */
object TurtleMotion {
    /** Updated by the launcher settings screen; volatile keeps Activity recreation safe. */
    @Volatile private var animationsEnabled = true

    fun configure(enabled: Boolean) {
        animationsEnabled = enabled
    }

    const val screenDurationMillis = 320
    const val dialogDurationMillis = 260
    const val listStepMillis = 28

    val screenEnter: EnterTransition
        get() = if (!animationsEnabled) EnterTransition.None else fadeIn(tween(screenDurationMillis, easing = FastOutSlowInEasing)) +
            slideInVertically(
                initialOffsetY = { it / 8 },
                animationSpec = tween(screenDurationMillis, easing = FastOutSlowInEasing),
            )

    val screenExit: ExitTransition
        get() = if (!animationsEnabled) ExitTransition.None else fadeOut(tween(240, easing = LinearOutSlowInEasing)) +
            slideOutVertically(
                targetOffsetY = { -it / 12 },
                animationSpec = tween(240, easing = LinearOutSlowInEasing),
            )

    /** Resolves Turtle's persisted transition keys without coupling Compose to settings storage. */
    fun enter(key: String?): EnterTransition {
        if (!animationsEnabled) return EnterTransition.None
        return when (key) {
        "slide_down" -> fadeIn(tween(screenDurationMillis)) +
            slideInVertically(initialOffsetY = { -it / 8 }, animationSpec = tween(screenDurationMillis))
        "slide_left" -> fadeIn(tween(screenDurationMillis)) +
            slideInHorizontally(initialOffsetX = { -it / 8 }, animationSpec = tween(screenDurationMillis))
        "slide_right" -> fadeIn(tween(screenDurationMillis)) +
            slideInHorizontally(initialOffsetX = { it / 8 }, animationSpec = tween(screenDurationMillis))
        "bounce" -> fadeIn(tween(screenDurationMillis)) +
            scaleIn(initialScale = 0.82f, animationSpec = spring(dampingRatio = 0.58f))
        "fade_in" -> fadeIn(tween(screenDurationMillis))
        "zoom_in", "zoom" -> fadeIn(tween(screenDurationMillis)) +
            scaleIn(initialScale = if (key == "zoom") 0.84f else 0.72f, animationSpec = tween(screenDurationMillis))
        "sheet" -> sheetEnter
        else -> screenEnter
        }
    }

    fun exit(key: String?): ExitTransition {
        if (!animationsEnabled) return ExitTransition.None
        return when (key) {
        "slide_down" -> fadeOut(tween(240)) +
            slideOutVertically(targetOffsetY = { it / 12 }, animationSpec = tween(240))
        "slide_left" -> fadeOut(tween(240)) +
            slideOutHorizontally(targetOffsetX = { it / 12 }, animationSpec = tween(240))
        "slide_right" -> fadeOut(tween(240)) +
            slideOutHorizontally(targetOffsetX = { -it / 12 }, animationSpec = tween(240))
        "bounce" -> fadeOut(tween(220)) +
            scaleOut(targetScale = 0.82f, animationSpec = spring(dampingRatio = 0.7f))
        "fade_out", "fade_in" -> fadeOut(tween(220))
        "zoom_out", "zoom" -> fadeOut(tween(220)) +
            scaleOut(targetScale = if (key == "zoom") 0.84f else 0.72f, animationSpec = tween(220))
        "sheet" -> sheetExit
        else -> screenExit
        }
    }

    val sheetEnter: EnterTransition
        get() = if (!animationsEnabled) EnterTransition.None else fadeIn(tween(dialogDurationMillis)) +
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(dialogDurationMillis, easing = FastOutSlowInEasing),
            )

    val sheetExit: ExitTransition
        get() = if (!animationsEnabled) ExitTransition.None else fadeOut(tween(220)) +
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(220, easing = LinearOutSlowInEasing),
            )

    val dialogEnter: EnterTransition
        get() = if (!animationsEnabled) EnterTransition.None else fadeIn(tween(dialogDurationMillis)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(dialogDurationMillis, easing = FastOutSlowInEasing),
            )

    val dialogExit: ExitTransition
        get() = if (!animationsEnabled) ExitTransition.None else fadeOut(tween(200)) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(200, easing = LinearOutSlowInEasing),
            )

    /** Turtle's auxiliary effects, expressed as cheap graphics-layer transforms. */
    fun pulse(enabled: Boolean = true): Modifier = Modifier.composed {
        if (!enabled || !animationsEnabled) return@composed Modifier
        val transition = androidx.compose.animation.core.rememberInfiniteTransition()
        val scale by transition.animateFloat(
            initialValue = 0.98f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        )
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    }

    fun wobble(enabled: Boolean = true): Modifier = Modifier.composed {
        if (!enabled || !animationsEnabled) return@composed Modifier
        val transition = androidx.compose.animation.core.rememberInfiniteTransition()
        val rotation by transition.animateFloat(
            initialValue = -1.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse),
        )
        Modifier.graphicsLayer { rotationZ = rotation }
    }

    fun shake(enabled: Boolean = true): Modifier = Modifier.composed {
        if (!enabled || !animationsEnabled) return@composed Modifier
        val transition = androidx.compose.animation.core.rememberInfiniteTransition()
        val offset by transition.animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(70), RepeatMode.Reverse),
        )
        Modifier.graphicsLayer { translationX = offset }
    }

    /** Adds Turtle's subtle press compression without allocating a ripple on every frame. */
    fun pressClickable(onClick: () -> Unit): Modifier = Modifier.composed {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (animationsEnabled && pressed) 0.97f else 1f,
            animationSpec = tween(120),
            label = "turtle-press-scale",
        )
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
    }

    fun tabTransform(forward: Boolean = true): ContentTransform {
        if (!animationsEnabled) return EnterTransition.None togetherWith ExitTransition.None
        val enterOffset = if (forward) 1 else -1
        val exitOffset = -enterOffset
        return (
            fadeIn(tween(260)) + slideInHorizontally(
                initialOffsetX = { it / 5 * enterOffset },
                animationSpec = tween(260, easing = FastOutSlowInEasing),
            )
        ) togetherWith (
            fadeOut(tween(190)) + slideOutHorizontally(
                targetOffsetX = { it / 6 * exitOffset },
                animationSpec = tween(190, easing = LinearOutSlowInEasing),
            )
        )
    }

    fun listItemEnter(index: Int): EnterTransition = if (!animationsEnabled) {
        EnterTransition.None
    } else fadeIn(tween(260, delayMillis = (index.coerceAtMost(8)) * listStepMillis)) +
            slideInVertically(
                initialOffsetY = { it / 10 },
                animationSpec = tween(
                    260,
                    delayMillis = (index.coerceAtMost(8)) * listStepMillis,
                    easing = FastOutSlowInEasing,
                ),
            )
}
