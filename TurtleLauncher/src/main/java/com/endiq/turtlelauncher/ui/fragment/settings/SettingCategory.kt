package com.endiq.turtlelauncher.ui.fragment.settings

enum class SettingCategory {
    VIDEO, CONTROL, GAME, JAVA, HUD, OPTIMIZATION, LAUNCHER, EXPERIMENTAL, PHONE, ACCESSIBILITY, RECORDING,
    /** Mouse & Keyboard - dedicated mouse/keyboard controls screen (Zalith Launcher 2 port).
     *  Appended at the end on purpose: SettingsPageSwapEvent matches on ordinal. */
    MOUSE_KEYBOARD
}