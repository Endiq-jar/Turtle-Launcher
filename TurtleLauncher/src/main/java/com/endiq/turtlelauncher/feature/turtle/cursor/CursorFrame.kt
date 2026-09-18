package com.endiq.turtlelauncher.feature.turtle.cursor

import android.graphics.Bitmap

data class CursorFrame(
    val bitmap: Bitmap,
    val hotspotX: Int,
    val hotspotY: Int,
    val delayMs: Long
)
