package com.endiq.turtlelauncher.feature.turtle.cursor

import android.graphics.Bitmap

/**
 * TurtleLauncher: a single decoded frame of a static (.cur) or animated (.ani) cursor.
 *
 * @param bitmap decoded ARGB_8888 frame image
 * @param hotspotX X hotspot offset, in [bitmap]'s own pixel space, that should align with
 *                 the logical pointer position (0,0 if unknown/not applicable)
 * @param hotspotY Y hotspot offset, in [bitmap]'s own pixel space
 * @param delayMs how long this frame should be displayed before advancing to the next one,
 *                in milliseconds. Only meaningful for multi-frame (.ani) sequences.
 */
data class CursorFrame(
    val bitmap: Bitmap,
    val hotspotX: Int,
    val hotspotY: Int,
    val delayMs: Long
)
