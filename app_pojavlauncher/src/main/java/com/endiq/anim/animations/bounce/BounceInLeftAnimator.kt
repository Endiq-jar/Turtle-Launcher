package com.endiq.anim.animations.bounce

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class BounceInLeftAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            fadeIn(target),
            slideWithSettle(target, "translationX", -travel(target, 0.55f))
        )
    }
}
