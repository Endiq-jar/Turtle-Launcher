package com.endiq.anim.animations.other

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class ShakeAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        val unit = dp(target, 3.5f)
        return arrayOf(
            ObjectAnimator.ofFloat(
                target, "translationX",
                0f, 4f * unit, -4f * unit, 2.6f * unit, -2.6f * unit, 1.4f * unit, -1.4f * unit, 0f
            ).apply { interpolator = easeStandard }
        )
    }
}
