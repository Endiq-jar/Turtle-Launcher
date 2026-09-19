package com.endiq.anim.animations.zoom

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class ZoomInLeftAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeIn(target),
            *scale(target, 0.62f, 1f, overshoot(1.05f)),
            translate(target, "translationX", -travel(target, 0.5f), 0f)
        )
    }
}
