package com.endiq.turtlelauncher.utils.anim

import android.view.View
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.utils.anim.ViewAnimUtils.Companion.setViewAnim


class AnimUtils {
    companion object {
        @JvmStatic
        fun setVisibilityAnim(view: View, shouldShow: Boolean) {
            setVisibilityAnim(view, shouldShow, 300, null)
        }

        @JvmStatic
        fun setVisibilityAnim(view: View, shouldShow: Boolean, listener: AnimationListener?) {
            setVisibilityAnim(view, shouldShow, 300, listener)
        }

        @JvmStatic
        fun setVisibilityAnim(view: View, shouldShow: Boolean, duration: Int) {
            setVisibilityAnim(view, shouldShow, duration, null)
        }

        @JvmStatic
        fun setVisibilityAnim(
            view: View,
            shouldShow: Boolean,
            duration: Int,
            listener: AnimationListener?
        ) {
            setVisibilityAnim(view, 0, shouldShow, duration, listener)
        }

        @JvmStatic
        fun playVisibilityAnim(view: View, visible: Boolean) {
            val targetVisibility = if (visible) View.VISIBLE else View.GONE
            if (view.visibility == targetVisibility) return

            // TurtleLauncher: was a hardcoded FadeIn/FadeOut pair. playVisibilityAnim() is the
            // app's generic "show/hide this view" helper, so routing it through
            // TurtleTransitions means the Settings transition pickers reach every in-place
            // show/hide too, not just fragment swaps - which is what makes them app-wide.
            setViewAnim(view, if (visible) TurtleTransitions.enter() else TurtleTransitions.exit(),
                (AllSettings.animationSpeed.getValue() * 0.7).toLong(),
                { view.visibility = View.VISIBLE },
                { view.visibility = if (visible) View.VISIBLE else View.GONE })
        }

        /**
         * Convenience helper for hide animations.
         * @param view the view to animate
         * @param startDelay delay before the animation starts
         * @param shouldShow true: show, false: hide
         * @param duration how long the animation runs
         * @param listener animation listener for the start/end callbacks
         */
        @JvmStatic
        fun setVisibilityAnim(
            view: View,
            startDelay: Int,
            shouldShow: Boolean,
            duration: Int,
            listener: AnimationListener?
        ) {
            listener?.onStart()

            if (shouldShow && view.visibility != View.VISIBLE) {
                fadeAnim(view, startDelay.toLong(), 0f, 1f, duration) {
                    view.visibility = View.VISIBLE
                    listener?.onEnd()
                }
            } else if (!shouldShow && view.visibility != View.GONE) {
                fadeAnim(view, startDelay.toLong(), view.alpha, 0f, duration) {
                    view.visibility = View.GONE
                    listener?.onEnd()
                }
            }
        }

        /**
         * Convenience helper for fade in/out animations.
         * @param view the view to animate
         * @param startDelay delay before the animation starts
         * @param begin starting alpha
         * @param end ending alpha
         * @param duration how long the animation runs
         * @param endAction task executed when the animation ends
         */
        @JvmStatic
        fun fadeAnim(
            view: View,
            startDelay: Long,
            begin: Float,
            end: Float,
            duration: Int,
            endAction: Runnable?
        ) {
            if ((view.visibility != View.VISIBLE && end == 0f) || (view.visibility == View.VISIBLE && end == 1f)) {
                endAction?.let { r -> Task.runTask { r.run() }.execute() }
                return
            }
            view.visibility = View.VISIBLE
            view.alpha = begin
            view.animate()
                .alpha(end)
                .setStartDelay(startDelay)
                .setDuration(duration.toLong())
                .withEndAction(endAction)
        }
    }

    interface AnimationListener {
        fun onStart()
        fun onEnd()
    }
}
