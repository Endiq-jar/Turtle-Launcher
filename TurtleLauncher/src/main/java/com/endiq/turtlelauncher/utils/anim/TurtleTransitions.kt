package com.endiq.turtlelauncher.utils.anim

import android.view.View
import com.endiq.anim.AnimCallback
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import android.content.Context
import android.provider.Settings
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import androidx.recyclerview.widget.RecyclerView
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.context.ContextExecutor


/** How a screen arrives. Values are stored in AllSettings.animationEnter. */
enum class EnterTransition(val key: String, val animation: Animations) {
    SLIDE_UP("slide_up", Animations.SlideInUp),
    SLIDE_DOWN("slide_down", Animations.SlideInDown),
    SLIDE_LEFT("slide_left", Animations.SlideInLeft),
    SLIDE_RIGHT("slide_right", Animations.SlideInRight),
    BOUNCE("bounce", Animations.BounceInUp),
    FADE_IN("fade_in", Animations.FadeInUp),
    ZOOM_IN("zoom_in", Animations.ZoomInUp),
    ZOOM("zoom", Animations.ZoomIn),
    SHEET("sheet", Animations.SheetIn),
    ;

    companion object {
        fun fromKey(key: String?): EnterTransition =
            values().firstOrNull { it.key == key } ?: SLIDE_UP
    }
}

/** How a screen leaves. Values are stored in AllSettings.animationExit. */
enum class ExitTransition(val key: String, val animation: Animations) {
    SLIDE_UP("slide_up", Animations.SlideOutUp),
    SLIDE_DOWN("slide_down", Animations.SlideOutDown),
    SLIDE_LEFT("slide_left", Animations.SlideOutLeft),
    SLIDE_RIGHT("slide_right", Animations.SlideOutRight),
    BOUNCE("bounce", Animations.BounceShrink),
    FADE_OUT("fade_out", Animations.FadeOutUp),
    ZOOM_OUT("zoom_out", Animations.ZoomOutUp),
    ZOOM("zoom", Animations.ZoomOut),
    SHEET("sheet", Animations.SheetOut),
    ;

    companion object {
        fun fromKey(key: String?): ExitTransition =
            values().firstOrNull { it.key == key } ?: SLIDE_UP
    }
}

object TurtleTransitions {
    /**
     * The single gate for "should we animate right now".
     *
     * A fragment transition can fire while the launcher Activity is stopped behind a live
     * Minecraft session (e.g. an EventBus-driven page swap queued just before the game
     * started). Ticking an AnimatorSet nobody is looking at just burns CPU and GC at the worst
     * possible moment, so an active game session is treated exactly like the user's own
     * "disable animations" toggle.
     */
    @JvmStatic
    fun isEnabled(): Boolean =
        AllSettings.animation.getValue() &&
        AllSettings.animationSpeed.getValue() > 0 &&
        !TaskExecutors.isGameSessionActive &&
        systemAllowsAnimations()

    private const val ANIMATION_SCALE_OFF = 0f

    /** Most rows animated in one pass - see [animateList]. */
    private const val MAX_ANIMATED_ROWS = 8

    @Volatile
    private var systemAllowsAnimations: Boolean? = null

    /** Cleared when the launcher resumes, so if the user has just been off changing the
     *  system animation scale or reduced-motion setting, we pick it up. */
    @JvmStatic
    fun onResume() {
        systemAllowsAnimations = null
    }

    private fun systemAllowsAnimations(): Boolean {
        systemAllowsAnimations?.let { return it }
        val allowed = querySystemAllowsAnimations()
        systemAllowsAnimations = allowed
        return allowed
    }

    private fun querySystemAllowsAnimations(): Boolean {
        val context = try {
            ContextExecutor.getApplication()
        } catch (e: RuntimeException) {
            return true
        }

        // Developer options -> "Remove animations", or any OEM battery/performance mode that
        // zeroes the scale. Available since API 16.
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        if (scale == ANIMATION_SCALE_OFF) return false

        return true
    }

    // ============================== Screens ==============================

    @JvmStatic
    fun enter(): Animations = EnterTransition.fromKey(AllSettings.animationEnter.getValue()).animation

    @JvmStatic
    fun exit(): Animations = ExitTransition.fromKey(AllSettings.animationExit.getValue()).animation

    @JvmStatic
    fun applyEnter(animPlayer: AnimPlayer, view: View) {
        animPlayer.apply(AnimPlayer.Entry(view, enter()))
    }

    @JvmStatic
    fun applyExit(animPlayer: AnimPlayer, view: View) {
        animPlayer.apply(AnimPlayer.Entry(view, exit()))
    }

    // =============================== Views ===============================

    /**
     * Plays the configured enter/exit animation on a standalone view - use this anywhere a
     * view is shown or hidden in place (progress spinners, empty-state panels, the join-code
     * row, ...) so those follow the same style as the screen transitions.
     *
     * Returns false (and does nothing) when animations are off, so callers can skip any
     * work they'd only do to prepare for an animation.
     */
    @JvmStatic
    @JvmOverloads
    fun animateView(
        view: View,
        appearing: Boolean,
        onStart: AnimCallback? = null,
        onEnd: AnimCallback? = null,
    ): Boolean {
        if (!isEnabled()) return false

        val player = AnimPlayer.play().apply(AnimPlayer.Entry(view, if (appearing) enter() else exit()))
        if (onStart != null) player.setOnStart(onStart)
        if (onEnd != null) player.setOnEnd(onEnd)
        player.start()
        return true
    }

    @JvmStatic
    fun sheetEnter(): Animations = Animations.SheetIn

    @JvmStatic
    fun sheetExit(): Animations = Animations.SheetOut

    /**
     * Plays the enter animation across a group of views, each one a beat after the last.
     *
     * Animating a row of panels all at once reads as one flat block moving; staggering them
     * by a few tens of milliseconds is what makes a screen feel like it assembled itself.
     */
    @JvmStatic
    @JvmOverloads
    fun stagger(animPlayer: AnimPlayer, views: List<View?>, stepMs: Long = 45L) {
        if (!isEnabled()) return
        var index = 0
        for (view in views) {
            if (view == null) continue
            // Each entry gets its own startDelay inside the one AnimatorSet, so the panels
            // arrive one after another instead of as a single flat block.
            val animators = enter().animator.getAnimators(view)
            animators.forEach { it.startDelay = index * stepMs }
            animPlayer.addAll(animators)
            index++
        }
    }

    /** Convenience for callers that aren't inside a fragment's slideIn() and so don't have an
     *  AnimPlayer handed to them. */
    @JvmStatic
    @JvmOverloads
    fun stagger(views: List<View?>, stepMs: Long = 45L) {
        if (!isEnabled()) return
        val player = AnimPlayer.play()
        stagger(player, views, stepMs)
        player.start()
    }

    /**
     * Staggered entry for every row currently on screen in a list. Call once after the
     * adapter's first data set is in (`recyclerView.post { ... }` is usually needed so the
     * children exist) - it animates what's visible and leaves scrolling alone.
     */
    @JvmStatic
    @JvmOverloads
    fun animateList(recyclerView: RecyclerView, stepMs: Long = 28L) {
        if (!isEnabled()) return

        // Never animate during a scroll. Entry animations and a fling compete for the same
        // main-thread frame budget, and the fling is what the user is actually watching - so
        // during a fling the list gets dropped frames and the animation gets ignored anyway.
        // Anything already on screen when the list settles is still animated on the next pass.
        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return

        // Cap how many rows animate at once. On a tablet a full-height list can hold 30+
        // attached children; animating all of them means 30 concurrent AnimatorSets and 30
        // hardware layers, which is a lot of GPU memory and a very visible hitch on exactly
        // the big-screen devices that can least afford it. Rows past the cap simply appear -
        // by that point down the screen the user isn't looking at them anyway.
        val children = (0 until recyclerView.childCount)
            .mapNotNull { recyclerView.getChildAt(it) }
            .filter { it.isAttachedToWindow }
            .take(MAX_ANIMATED_ROWS)

        stagger(children, stepMs)
    }

    @JvmStatic
    fun listLayoutAnimationController(context: Context): LayoutAnimationController =
        LayoutAnimationController(
            AnimationUtils.loadAnimation(context, R.anim.fade_downwards)
        ).apply {
            delay = 0.12f
            order = LayoutAnimationController.ORDER_NORMAL
        }

    /**
     * Press feedback for views that aren't an `AnimRelativeLayout` (which already carries a
     * press-scale state list animator). Squashes to [scale] on touch and springs back on
     * release, so tapping a plain button feels like tapping something.
     */
    @JvmStatic
    @JvmOverloads
    fun attachPressFeedback(view: View, scale: Float = 0.94f) {
        if (!isEnabled()) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(110).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.0f)).start()
                    // Let the click still happen.
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                }
            }
            false
        }
    }

    /** Show/hide a view with the configured transition, including the visibility flip.
     *  No-op if the view is already in the target state or animations are disabled (in which
     *  case the visibility is simply set immediately). */
    @JvmStatic
    fun setVisibilityAnimated(view: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == target) return

        if (!isEnabled()) {
            view.visibility = target
            return
        }

        view.visibility = View.VISIBLE
        animateView(view, visible, onEnd = { view.visibility = target })
    }
}
