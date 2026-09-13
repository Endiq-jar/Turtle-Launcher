package net.kdt.pojavlaunch.customcontrols.mouse;

import com.endiq.turtlelauncher.setting.AllSettings;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;

/**
 * Resolves the mouse buttons fired by the in-game touch gestures.
 *
 * TurtleLauncher, ported from Zalith Launcher 2's GestureActionType + its
 * gestureTapMouseAction / gestureLongPressMouseAction settings: upstream Pojav hardcodes
 * "quick tap = right button" (RightClickGesture) and "long press = hold left button"
 * (LeftClickGesture). Zalith made both user-configurable with the same defaults, and this
 * is that mapping. Values are the strings stored by the Settings -> Mouse & Keyboard list
 * rows ("left"/"right"); anything unrecognized falls back to the upstream default so a
 * corrupted preference can never send a garbage button code to GLFW.
 */
public final class GestureButtons {
    private GestureButtons() {}

    /** Button sent by the quick-tap gesture. Zalith2 default: MOUSE_RIGHT. */
    public static int tapButton() {
        return resolve(AllSettings.getGestureTapMouseAction().getValue(),
                LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT);
    }

    /** Button held by the long-press gesture. Zalith2 default: MOUSE_LEFT. */
    public static int longPressButton() {
        return resolve(AllSettings.getGestureLongPressMouseAction().getValue(),
                LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static int resolve(String value, int fallback) {
        if (value == null) return fallback;
        switch (value) {
            case "left":  return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
            case "right": return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
            default:      return fallback;
        }
    }
}
