package com.endiq.anim.animations.bounce

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class BounceShrinkAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeOut(target),
            *scale(target, 1f, 0.72f, anticipate(2.0f))
        )
    }
}
