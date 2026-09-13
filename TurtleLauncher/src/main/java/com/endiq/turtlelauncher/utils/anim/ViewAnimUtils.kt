package com.endiq.turtlelauncher.utils.anim

import android.view.View
import com.endiq.anim.AnimCallback
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations

class ViewAnimUtils {
    companion object {
        @JvmStatic
        fun setViewAnim(view: View, animations: Animations) {
            getAnimPlayer(view, animations).start()
        }

        @JvmStatic
        fun setViewAnim(view: View, animations: Animations, duration: Long) {
            getAnimPlayer(view, animations).duration(duration).start()
        }

        @JvmStatic
        fun setViewAnim(view: View, animations: Animations, onStart: AnimCallback, onEnd: AnimCallback) {
            getAnimPlayer(view, animations).setOnStart(onStart).setOnEnd(onEnd).start()
        }

        @JvmStatic
        fun setViewAnim(view: View, animations: Animations, duration: Long, onStart: AnimCallback, onEnd: AnimCallback) {
            getAnimPlayer(view, animations).duration(duration).setOnStart(onStart).setOnEnd(onEnd).start()
        }

        private fun getAnimPlayer(view: View, animations: Animations) = AnimPlayer.play().apply(AnimPlayer.Entry(view, animations))
    }
}