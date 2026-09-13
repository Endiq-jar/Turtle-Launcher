package com.endiq.turtlelauncher.feature.background

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.request.target.DrawableImageViewTarget

/**
 * Load callback for the DrawableImageViewTarget class.
 */
class CallbackDrawableImageViewTarget(
    private val imageView: ImageView,
    private val callback: Callback?
) : DrawableImageViewTarget(imageView) {
    override fun setResource(resource: Drawable?) {
        imageView.post {
            super.setResource(resource)
            val isLoaded = resource != null
            callback?.callback(isLoaded)
        }
    }

    fun interface Callback {
        fun callback(loaded: Boolean)
    }
}