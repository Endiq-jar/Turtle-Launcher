package com.endiq.turtlelauncher.feature.turtle.cursor

interface CursorHotspotProvider {
    /** 0f (left edge) .. 1f (right edge) of the drawable's current bounds width. */
    fun getHotspotFractionX(): Float

    /** 0f (top edge) .. 1f (bottom edge) of the drawable's current bounds height. */
    fun getHotspotFractionY(): Float
}
