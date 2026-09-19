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
