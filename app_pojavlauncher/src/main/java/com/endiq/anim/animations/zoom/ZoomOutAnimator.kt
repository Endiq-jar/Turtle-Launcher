package com.endiq.anim.animations.zoom

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class ZoomOutAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeOut(target),
            *scale(target, 1f, 0.55f, easeIn)
        )
    }
}
