package com.endiq.anim.animations.other

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class WobbleAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        val unit = dp(target, 3f)
        return arrayOf(
            ObjectAnimator.ofFloat(
                target, "translationX",
                0f, -8f * unit, 6.5f * unit, -5f * unit, 3.5f * unit, -2f * unit, 0f
            ).apply { interpolator = easeStandard },
            ObjectAnimator.ofFloat(
                target, "rotation", 0f, -5f, 3f, -3f, 2f, -1f, 0f
            ).apply { interpolator = easeStandard }
        )
    }
}
