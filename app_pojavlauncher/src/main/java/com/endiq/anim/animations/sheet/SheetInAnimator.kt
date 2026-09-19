package com.endiq.anim.animations.sheet

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class SheetInAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeIn(target),
            slideWithSettle(target, "translationY", screenHeight(target), 16f)
        )
    }
}
