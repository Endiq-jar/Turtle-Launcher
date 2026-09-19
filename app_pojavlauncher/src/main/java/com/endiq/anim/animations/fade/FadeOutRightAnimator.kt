package com.endiq.anim.animations.fade

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class FadeOutRightAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeOut(target),
            translate(target, "translationX", 0f, drift(target), easeIn)
        )
    }
}
