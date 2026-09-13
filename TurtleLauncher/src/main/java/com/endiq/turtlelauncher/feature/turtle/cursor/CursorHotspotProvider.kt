package com.endiq.turtlelauncher.feature.turtle.cursor

/**
 * TurtleLauncher: implemented by cursor drawables that know their own authored hotspot
 * (the point that should sit at the actual logical pointer position, e.g. the tip of an
 * arrow rather than its top-left corner).
 *
 * Expressed as a fraction of the drawable's current bounds rather than raw source pixels,
 * so a caller can apply it correctly no matter what size the drawable has been scaled to.
 */
interface CursorHotspotProvider {
    /** 0f (left edge) .. 1f (right edge) of the drawable's current bounds width. */
    fun getHotspotFractionX(): Float

    /** 0f (top edge) .. 1f (bottom edge) of the drawable's current bounds height. */
    fun getHotspotFractionY(): Float
}
