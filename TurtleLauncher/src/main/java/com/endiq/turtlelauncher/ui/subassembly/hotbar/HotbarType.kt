package com.endiq.turtlelauncher.ui.subassembly.hotbar

import com.endiq.turtlelauncher.R

/**
 * Hotbar hitbox type.
 * @param nameId localised name id of the type
 * @param valueName stored settings value of the type
 */
enum class HotbarType(val nameId: Int, val valueName: String) {
    /**
     * Adaptive: compute a fitting hitbox width/height from the screen resolution and GUI scale
     * (may be imprecise).
     */
    AUTO(R.string.option_hotbar_type_auto, "auto"),

    /**
     * Manual: let the user adjust the hitbox width and height themselves.
     */
    MANUALLY(R.string.option_hotbar_type_manually, "manually")
}