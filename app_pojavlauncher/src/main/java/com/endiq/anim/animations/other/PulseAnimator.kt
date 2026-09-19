package com.endiq.anim.animations.other

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.BaseAnimator

class PulseAnimator : BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        return arrayOf(
            ObjectAnimator.ofFloat(target, "scaleY", 1f, 1.06f, 1f).apply { interpolator = easeStandard },
            ObjectAnimator.ofFloat(target, "scaleX", 1f, 1.06f, 1f).apply { interpolator = easeStandard }
        )
    }
}
