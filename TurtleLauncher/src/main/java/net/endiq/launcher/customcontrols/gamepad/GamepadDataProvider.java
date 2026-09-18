package net.endiq.launcher.customcontrols.gamepad;

import net.endiq.launcher.GrabListener;

public interface GamepadDataProvider {
    GamepadMap getMenuMap();
    GamepadMap getGameMap();
    boolean isGrabbing();
    void attachGrabListener(GrabListener grabListener);
}
