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

    /**
     * Default: animate the whole screen with whatever the user picked in Settings.
     *
     * TurtleLauncher: these used to be abstract, which forced all ~33 fragments to hardcode an
     * animation pair (BounceIn* in, FadeOut* out) whether or not they cared. The default now
     * comes from [TurtleTransitions], so a fragment only overrides these when it deliberately
     * animates a sub-panel instead of the root - and even then it should ask
     * [TurtleTransitions.applyEnter] / [TurtleTransitions.applyExit] for the animation itself
     * rather than naming one, so the Settings pickers still apply.
     */
    override fun slideIn(animPlayer: AnimPlayer) {
        view?.let { TurtleTransitions.applyEnter(animPlayer, it) }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        view?.let { TurtleTransitions.applyExit(animPlayer, it) }
    }

    private fun playAnimation(animationAction: (AnimPlayer) -> Unit) {
        // TurtleLauncher: Background Services (item 20) - a fragment transition can still
        // fire here while the launcher Activity is stopped behind an active Minecraft
        // session (e.g. an EventBus-driven page swap queued just before the game started).
        // Treat an active game session the same as the user's own "disable animations"
        // toggle: skip straight past AnimatorSet entirely rather than spending CPU/GC on
        // ticking a transition nobody is looking at. No new setting needed - reuses the
        // same on/off semantics AllSettings.animation already has here.
        if (TurtleTransitions.isEnabled()) {
            animPlayer.clearEntries()
            animPlayer.apply {
                animationAction(this)
                start()
            }
        }
    }
}
