package com.endiq.turtlelauncher.feature.turtle.cursor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer

/**
 * TurtleLauncher: real-time playback engine for a decoded cursor frame sequence
 * (produced by [AniDecoder] for .ani files, or a single-frame list from [CurIcoDecoder]
 * for plain .cur files).
 *
 * Frame advancement is driven by [Choreographer.postFrameCallback], i.e. the display's
 * own vsync signal, rather than a fixed postDelayed() poll - each vsync tick checks how
 * much real time has elapsed and advances however many frames are actually due (catching
 * up in one step after a jank instead of drifting), then calls [invalidateSelf] only when
 * the visible frame actually changed. Because this is a normal [Drawable], every host that
 * assigns it the standard way (ImageView.setImageDrawable, View.setBackground) gets a
 * working [Drawable.Callback] for free and redraws automatically - this is what gives the
 * settings-card preview and the touchpad test view smooth animation with no per-call-site
 * work. [net.kdt.pojavlaunch.customcontrols.mouse.Touchpad] draws its pointer manually in
 * onDraw() instead of through those APIs, so it wires the callback itself.
 */
class AnimatedCursorDrawable(
    private val frames: List<CursorFrame>
) : Drawable(), Animatable, CursorHotspotProvider, Choreographer.FrameCallback {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var frameIndex = 0
    private var frameStartUptimeMs = 0L
    private var running = false

    private val currentFrame get() = frames[frameIndex]

    override fun getIntrinsicWidth(): Int = currentFrame.bitmap.width
    override fun getIntrinsicHeight(): Int = currentFrame.bitmap.height

    override fun getHotspotFractionX(): Float =
        currentFrame.hotspotX.toFloat() / currentFrame.bitmap.width.coerceAtLeast(1)

    override fun getHotspotFractionY(): Float =
        currentFrame.hotspotY.toFloat() / currentFrame.bitmap.height.coerceAtLeast(1)

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(currentFrame.bitmap, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (frames.size <= 1 || running) return
        running = true
        frameStartUptimeMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun isRunning(): Boolean = running

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return

        val now = SystemClock.uptimeMillis()
        var elapsed = now - frameStartUptimeMs
        var advanced = false

        // Advance however many frames are actually due in one pass, so a dropped/late
        // vsync tick catches playback back up instead of just permanently slowing it down.
        var safety = frames.size * 4 // hard bound - never spin more than a few full loops
        while (elapsed >= currentFrame.delayMs && safety-- > 0) {
            elapsed -= currentFrame.delayMs
            frameIndex = (frameIndex + 1) % frames.size
            advanced = true
        }
        frameStartUptimeMs = now - elapsed

        if (advanced) invalidateSelf()
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }
}
