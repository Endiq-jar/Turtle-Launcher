package com.endiq.anim.animations.sheet

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class SheetOutAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeOut(target),
            translate(target, "translationY", 0f, screenHeight(target), easeIn)
        )
    }
}
