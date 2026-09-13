package com.endiq.anim

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.setting.AllSettings


class AnimPlayer {
    private var mAnimatorSet: AnimatorSet = AnimatorSet()
    private var mAnimators: MutableList<Animator> = ArrayList()
    private var mOnStartCallback: AnimCallback? = null
    private var mOnEndCallback: AnimCallback? = null
    private var mDuration: Long? = null
    private var mDelay: Long? = null

    fun clearEntries() {
        mAnimators.clear()
    }

    fun apply(entry: Entry): AnimPlayer {
        mAnimators.addAll(entry.animations.animator.getAnimators(entry.target))
        return this
    }

    /** TurtleLauncher: adds already-built animators directly. Needed for staggered entries -
     *  [apply] rebuilds animators from an [Animations] value, which leaves no way to offset
     *  individual entries, and per-view start delays are the whole point of a stagger. */
    fun addAll(animators: Array<Animator>): AnimPlayer {
        mAnimators.addAll(animators)
        return this
    }

    fun duration(long: Long): AnimPlayer {
        mDuration = long
        return this
    }

    fun delay(long: Long): AnimPlayer {
        mDelay = long
        return this
    }

    fun setOnStart(callback: AnimCallback): AnimPlayer {
        mOnStartCallback = callback
        return this
    }

    fun setOnEnd(callback: AnimCallback): AnimPlayer {
        mOnEndCallback = callback
        return this
    }

    fun start() {
        if (mAnimatorSet.isStarted || mAnimatorSet.isRunning) {
            stop()
        }

        // TurtleLauncher: promote every view we're about to animate to a hardware layer.
        //
        // Without this, each frame of a translate/scale/alpha animation invalidates the view
        // and re-runs the whole display list - its own draw plus every child's - on the CPU,
        // before the result is uploaded to the GPU anyway. On a full-screen fragment (or a
        // settings page with a hundred rows in it) that is the difference between a smooth
        // transition and a visibly stuttering one. With a layer, the subtree is drawn once
        // into a texture and afterwards each frame is just a transform of that texture.
        //
        // This is the one thing that makes the rest of the animation work affordable, so it
        // is done here rather than per-call-site: every animation in the launcher, including
        // the ~22 feedback animations, goes through AnimPlayer.
        val targets = mAnimators.mapNotNull { (it as? ObjectAnimator)?.target as? View }.distinct()
        val previousLayerTypes = targets.associateWith { it.layerType }
        targets.forEach { it.setLayerType(View.LAYER_TYPE_HARDWARE, null) }

        mAnimatorSet.apply {
            duration = mDuration ?: AllSettings.animationSpeed.getValue().toLong()
            startDelay = mDelay ?: 0

            removeAllListeners()
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    mOnStartCallback?.call()
                }

                override fun onAnimationEnd(animation: Animator) {
                    releaseLayers(targets, previousLayerTypes)
                    mOnEndCallback?.call()
                    clearState()
                }

                override fun onAnimationCancel(animation: Animator) {
                    releaseLayers(targets, previousLayerTypes)
                    clearState()
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
            playTogether(mAnimators)
            start()
        }
    }

    /**
     * Drops the hardware layers again. Leaving a view on a hardware layer costs GPU memory for
     * as long as it lives, so this has to run on cancel as well as on end - and it restores
     * whatever layer type the view had before, rather than assuming NONE, in case a view set
     * one deliberately.
     */
    private fun releaseLayers(targets: List<View>, previous: Map<View, Int>) {
        for (view in targets) {
            view.setLayerType(previous[view] ?: View.LAYER_TYPE_NONE, null)
        }
    }

    fun stop() {
        if (mAnimatorSet.isRunning) {
            mAnimatorSet.cancel()
            clearState()
        }
    }

    private fun clearState() {
        mAnimatorSet = AnimatorSet()
    }

    companion object {
        fun play(): AnimPlayer {
            return AnimPlayer()
        }
    }

    data class Entry(val target: View, val animations: Animations)
}
