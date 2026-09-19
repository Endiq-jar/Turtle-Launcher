package com.endiq.anim.animations.zoom

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class ZoomInAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeIn(target),
            *scale(target, 0.55f, 1f, overshoot(1.05f))
        )
    }
}
