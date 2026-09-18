package net.endiq.launcher.customcontrols.mouse;

import com.endiq.turtlelauncher.setting.AllSettings;

import net.endiq.launcher.LwjglGlfwKeycode;

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
