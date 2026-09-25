package kr.co.donghyun.turtlelauncher.feature.turtle

import android.content.Context
import kr.co.donghyun.turtlelauncher.data.key.ControlPresetManager
import kr.co.donghyun.turtlelauncher.data.key.KeyButton

/**
 * Android-side equivalent of the original Turtle ControlSwitcher. The game menu uses this to
 * cycle the two bundled layouts in filename order while keeping the selected layout persistent.
 */
object ControlSwitcher {
    fun layouts(context: Context): List<String> = ControlPresetManager.availablePresets(context)

    fun cycleToNext(context: Context): Pair<String, List<KeyButton>> =
        ControlPresetManager.cycle(context)
}
