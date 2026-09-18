package com.endiq.turtlelauncher.ui.fragment

import com.endiq.anim.AnimPlayer
import com.endiq.turtlelauncher.utils.anim.SlideAnimation
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

abstract class FragmentWithAnim : BaseFragment, SlideAnimation {
    private var animPlayer: AnimPlayer = AnimPlayer()

    constructor() : super()

    constructor(contentLayoutId: Int) : super(contentLayoutId)

    override fun onStart() {
        super.onStart()
        slideIn()
    }

    fun slideIn() {
        playAnimation { slideIn(it) }
    }

    fun slideOut() {
        playAnimation { slideOut(it) }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        view?.let { TurtleTransitions.applyEnter(animPlayer, it) }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        view?.let { TurtleTransitions.applyExit(animPlayer, it) }
    }

    private fun playAnimation(animationAction: (AnimPlayer) -> Unit) {
        if (TurtleTransitions.isEnabled()) {
            animPlayer.clearEntries()
            animPlayer.apply {
                animationAction(this)
                start()
            }
        }
    }
}
