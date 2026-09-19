package com.endiq.turtlelauncher.feature.inputstats

import net.kdt.pojavlaunch.LwjglGlfwKeycode
import java.util.concurrent.ConcurrentHashMap

object InputStatsTracker {
    // Rolling 1-second window of left-click-down timestamps, for CPS.
    private val leftClickTimestamps = ArrayDeque<Long>()

    // Currently-held state for the keys/buttons the Keystrokes/Mousestrokes
    // HUD cares about. ConcurrentHashMap since input events arrive off the
    // UI thread but the HUD timer reads them from a different thread too.
    private val heldKeys = ConcurrentHashMap<Int, Boolean>()
    private val heldMouseButtons = ConcurrentHashMap<Int, Boolean>()

    @JvmStatic
    fun onKeyEvent(keycode: Int, isDown: Boolean) {
        if (isTrackedKey(keycode)) {
            heldKeys[keycode] = isDown
        }
    }

    @JvmStatic
    fun onMouseEvent(button: Int, isDown: Boolean) {
        if (button == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt() ||
            button == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        ) {
            heldMouseButtons[button] = isDown
        }

        if (button == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt() && isDown) {
            synchronized(leftClickTimestamps) {
                val now = System.currentTimeMillis()
                leftClickTimestamps.addLast(now)
                pruneOldClicks(now)
            }
        }
    }

    /** Clicks-per-second over the trailing 1-second window. */
    @JvmStatic
    fun getCps(): Int {
        synchronized(leftClickTimestamps) {
            pruneOldClicks(System.currentTimeMillis())
            return leftClickTimestamps.size
        }
    }

    @JvmStatic
    fun isKeyHeld(keycode: Int): Boolean = heldKeys[keycode] == true

    @JvmStatic
    fun isMouseButtonHeld(button: Int): Boolean = heldMouseButtons[button] == true

    @JvmStatic
    fun isLeftMouseHeld(): Boolean = isMouseButtonHeld(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt())

    @JvmStatic
    fun isRightMouseHeld(): Boolean = isMouseButtonHeld(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt())

    /** Resets all held-state when a game session ends, so stale "held" keys don't linger into the next session. */
    @JvmStatic
    fun reset() {
        heldKeys.clear()
        heldMouseButtons.clear()
        synchronized(leftClickTimestamps) { leftClickTimestamps.clear() }
    }

    private fun isTrackedKey(keycode: Int): Boolean = keycode == LwjglGlfwKeycode.GLFW_KEY_W.toInt() ||
        keycode == LwjglGlfwKeycode.GLFW_KEY_A.toInt() ||
        keycode == LwjglGlfwKeycode.GLFW_KEY_S.toInt() ||
        keycode == LwjglGlfwKeycode.GLFW_KEY_D.toInt() ||
        keycode == LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()

    private fun pruneOldClicks(now: Long) {
        while (leftClickTimestamps.isNotEmpty() && now - leftClickTimestamps.first() > 1000L) {
            leftClickTimestamps.removeFirst()
        }
    }
}
