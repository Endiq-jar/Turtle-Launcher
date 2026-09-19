package com.endiq.anim.animations

import android.animation.Animator
import android.view.View
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.view.animation.AnticipateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

abstract class BaseAnimator {

    abstract fun getAnimators(target: View): Array<Animator>

    /** Material "decelerate" - fast start, gentle landing. Right for anything arriving. */
    protected val easeOut: TimeInterpolator get() = AnimCurves.easeOut

    /** Material "accelerate" - slow start, leaves quickly. Right for anything departing. */
    protected val easeIn: TimeInterpolator get() = AnimCurves.easeIn

    /** Material "standard" - for moves that neither arrive nor leave (pulse, wobble). */
    protected val easeStandard: TimeInterpolator get() = AnimCurves.easeStandard

    protected val linear: TimeInterpolator get() = AnimCurves.linear

    /** Overshoots the target and springs back. */
    protected fun overshoot(tension: Float = 1.4f): TimeInterpolator =
        AnimCurves.overshoot(tension)

    /** Pulls back briefly before moving - gives a shrink a little "puff" first. */
    protected fun anticipate(tension: Float = 2.0f): TimeInterpolator =
        AnimCurves.anticipate(tension)

    // ============================= Distances =============================

    protected fun dp(target: View, value: Float): Float =
        value * target.resources.displayMetrics.density

    protected fun travel(
        target: View,
        fraction: Float = 0.30f,
        minDp: Float = 140f,
        maxDp: Float = 420f,
    ): Float {
        val dm = target.resources.displayMetrics
        val base = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val min = dp(target, minDp)
        val max = dp(target, maxDp)
        return (base * fraction).coerceIn(min, max)
    }

    /** A shorter hop, for the fade family's subtle drift. */
    protected fun drift(target: View): Float = travel(target, 0.11f, 48f, 160f)

    protected fun screenHeight(target: View): Float =
        target.resources.displayMetrics.heightPixels.toFloat()

    protected fun screenWidth(target: View): Float =
        target.resources.displayMetrics.widthPixels.toFloat()

    // ============================== Builders ==============================

    protected fun fadeIn(target: View): ObjectAnimator =
        ObjectAnimator.ofFloat(target, "alpha", 0f, 1f).apply { interpolator = easeOut }

    protected fun fadeOut(target: View): ObjectAnimator =
        ObjectAnimator.ofFloat(target, "alpha", 1f, 0f).apply { interpolator = easeIn }

    protected fun translate(
        target: View,
        property: String,
        from: Float,
        to: Float,
        interpolator: TimeInterpolator = easeOut,
    ): ObjectAnimator =
        ObjectAnimator.ofFloat(target, property, from, to).apply { this.interpolator = interpolator }

    protected fun scale(
        target: View,
        from: Float,
        to: Float,
        interpolator: TimeInterpolator,
    ): Array<ObjectAnimator> = arrayOf(
        ObjectAnimator.ofFloat(target, "scaleX", from, to).apply { this.interpolator = interpolator },
        ObjectAnimator.ofFloat(target, "scaleY", from, to).apply { this.interpolator = interpolator },
    )

    protected fun slideWithSettle(
        target: View,
        property: String,
        fromPx: Float,
        bounceDp: Float = 14f,
    ): ObjectAnimator {
        val bounce = dp(target, bounceDp)
        return ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofKeyframe(
                property,
                Keyframe.ofFloat(0.00f, fromPx),
                Keyframe.ofFloat(0.62f, fromPx * 0.12f), // covers most of the ground early
                Keyframe.ofFloat(0.78f, -bounce),        // overshoots past rest
                Keyframe.ofFloat(0.89f, bounce * 0.42f),
                Keyframe.ofFloat(0.96f, -bounce * 0.16f),
                Keyframe.ofFloat(1.00f, 0f),
            )
        ).apply { interpolator = linear }
    }
}

internal object AnimCurves {
    val easeOut: TimeInterpolator = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
    val easeIn: TimeInterpolator = PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f)
    val easeStandard: TimeInterpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)
    val linear: TimeInterpolator = LinearInterpolator()

    private val overshoots = HashMap<Float, TimeInterpolator>()
    private val anticipates = HashMap<Float, TimeInterpolator>()

    fun overshoot(tension: Float): TimeInterpolator = synchronized(overshoots) {
        overshoots.getOrPut(tension) { OvershootInterpolator(tension) }
    }

    fun anticipate(tension: Float): TimeInterpolator = synchronized(anticipates) {
        anticipates.getOrPut(tension) { AnticipateInterpolator(tension) }
    }
}
