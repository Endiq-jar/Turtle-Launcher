package com.endiq.anim.animations.fade

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class FadeInLeftAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeIn(target),
            translate(target, "translationX", -drift(target), 0f)
        )
    }
}
