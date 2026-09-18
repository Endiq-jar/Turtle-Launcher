package com.endiq.turtlelauncher.ui.view

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.endiq.turtlelauncher.R
import net.endiq.launcher.Tools

open class AnimButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        setRipple(attrs)
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.xml.anim_scale)
        translationZ = Tools.dpToPx(4f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post {
            pivotX = width / 2f
            pivotY = height / 2f
        }
    }

    /**
     * TurtleLauncher: `background` is *never* actually null here, even for a button whose
     * layout XML never mentions android:background at all - Button's own default style
     * (android.R.attr.buttonStyle, this class's own defStyleAttr) always supplies some
     * background drawable during the super() call above, before this even runs. That means
     * `instanceBackground ?: R.drawable.button_background` (the previous version of this
     * method) could never actually tell "this specific instance asked for a custom
     * background" apart from "no background was set, that's just the platform style's own
     * default" - `background` is non-null either way, so the R.drawable.button_background
     * fallback never fired for ANY button, and every ordinary button (the overwhelming
     * majority - almost none of them set android:background, they're meant to just get this
     * class's own themed default) silently rendered with the OS's flat grey Material button
     * style instead of this app's own themed drawable. Checking the raw AttributeSet for a
     * literal android:background entry - instead of the resolved `background` property -
     * is what actually distinguishes the two cases, fixing both bugs at once: an
     * instance-set background (e.g. play_button's bg_launch_button) still wins because it's
     * a real attribute on that tag, and every other button gets this app's own themed
     * background back instead of the OS default.
     */
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