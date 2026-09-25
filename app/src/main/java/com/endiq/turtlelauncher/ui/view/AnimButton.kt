package com.endiq.turtlelauncher.ui.view

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kr.co.donghyun.turtlelauncher.R

open class AnimButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        setRipple(attrs)
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.xml.anim_scale)
        translationZ = 4f * resources.displayMetrics.density
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post {
            pivotX = width / 2f
            pivotY = height / 2f
        }
    }

    private fun setRipple(attrs: AttributeSet?) {
        val hasExplicitBackground = attrs?.getAttributeValue(ANDROID_NS, "background") != null
        val contentDrawable = if (hasExplicitBackground) {
            background
        } else {
            ResourcesCompat.getDrawable(resources, R.drawable.button_background, context.theme)
        }

        val rippleDrawable = RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.background_ripple_effect)),
            contentDrawable,
            null
        )

        background = rippleDrawable
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
