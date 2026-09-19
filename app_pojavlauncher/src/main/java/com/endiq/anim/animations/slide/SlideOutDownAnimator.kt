package com.endiq.anim.animations.slide

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class SlideOutDownAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeOut(target),
            translate(target, "translationY", 0f, travel(target), easeIn)
        )
    }
}
