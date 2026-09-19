package com.endiq.anim.animations.slide

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class SlideInUpAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeIn(target),
            translate(target, "translationY", travel(target), 0f)
        )
    }
}
