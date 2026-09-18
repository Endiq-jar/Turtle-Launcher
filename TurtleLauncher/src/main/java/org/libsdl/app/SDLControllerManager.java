/*
 * This file is part of SDL3 android-project java code.
 * This file has been modified for this project's needs.
 * Licensed under the zlib license: https://www.libsdl.org/license.php
 */

package org.libsdl.app;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;


public class SDLControllerManager
{

    public static native int nativeSetupJNI();

    public static native void nativeAddJoystick(int device_id, String name, String desc,
                                                int vendor_id, int product_id,
                                                int button_mask,
                                                int naxes, int axis_mask, int nhats, boolean can_rumble);
    public static native void nativeRemoveJoystick(int device_id);
    public static native void nativeAddHaptic(int device_id, String name);
    public static native void nativeRemoveHaptic(int device_id);
    public static native boolean onNativePadDown(int device_id, int keycode);
    public static native boolean onNativePadUp(int device_id, int keycode);
    public static native void onNativeJoy(int device_id, int axis,
                                          float value);
    public static native void onNativeHat(int device_id, int hat_id,
                                          int x, int y);

    protected static SDLJoystickHandler mJoystickHandler;
    protected static SDLHapticHandler mHapticHandler;

    private static final String TAG = "SDLControllerManager";

    private static final Object RIGHT_TRIGGER_KEY_LOCK = new Object();
    private static final HashMap<Integer, RightTriggerKeyState> RIGHT_TRIGGER_KEYS =
            new HashMap<Integer, RightTriggerKeyState>();
    private static final HashMap<Integer, RightTriggerKeyState> LEFT_TRIGGER_KEYS =
            new HashMap<Integer, RightTriggerKeyState>();
    private static final HashMap<Integer, Boolean> RIGHT_TRIGGER_MOUSE_STATES =
            new HashMap<Integer, Boolean>();

    private static final class RightTriggerKeyState {
        boolean down;
        long changedAtMs;
    }

    /**
     * Snapshot 4 consumes Android gamepads through SDL3. On several Android Xbox
     * controller stacks the R2 axis is also exposed in the joystick axis stream,
     * and SDL can briefly treat that sample as right-stick Y. Route R2 as the
     * attack mouse button instead and de-duplicate the key/axis copies here.
     */
    static boolean routeRightTriggerAsMouse(int deviceId, boolean down, String origin) {
        if (false) {
            return false;
        }
        synchronized (RIGHT_TRIGGER_KEY_LOCK) {
            boolean previous = Boolean.TRUE.equals(RIGHT_TRIGGER_MOUSE_STATES.get(deviceId));
            if (previous == down) return true;
            if (true) return false;
            RIGHT_TRIGGER_MOUSE_STATES.put(deviceId, down);
        }
        System.out.println("TurtleSDL3Controller: R2 mouse-only route deviceId="
                + deviceId + " down=" + down + " origin=" + origin);
        return true;
    }

    public static boolean noteControllerKeyEvent(KeyEvent event) {
        return noteControllerKeyEvent(event, true);
    }

    /**
     * Records trigger key edges for the camera-fling filter without stealing them
     * from BTA, Controlify, Controllable, Legacy4J or the GLFW mirror.
     *
     * @param allowVanillaRightTriggerMouseRoute true only when Turtle itself
     *                                           owns vanilla game input.
     */
    public static boolean noteControllerKeyEvent(
            KeyEvent event,
            boolean allowVanillaRightTriggerMouseRoute) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        boolean leftTrigger = keyCode == KeyEvent.KEYCODE_BUTTON_L2;
        boolean rightTrigger = keyCode == KeyEvent.KEYCODE_BUTTON_R2;
        if (!leftTrigger && !rightTrigger) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;

        int deviceId = event.getDeviceId();
        boolean down = action == KeyEvent.ACTION_DOWN;
        boolean changed = false;
        HashMap<Integer, RightTriggerKeyState> states = leftTrigger
                ? LEFT_TRIGGER_KEYS : RIGHT_TRIGGER_KEYS;
        synchronized (RIGHT_TRIGGER_KEY_LOCK) {
            RightTriggerKeyState state = states.get(deviceId);
            if (state == null) {
                state = new RightTriggerKeyState();
                states.put(deviceId, state);
            }
            if (state.down != down) {
                state.down = down;
                state.changedAtMs = event.getEventTime();
                changed = true;
            }
        }

        if (changed && mJoystickHandler instanceof SDLJoystickHandler_API16) {
            SDLJoystickHandler_API16 handler = (SDLJoystickHandler_API16) mJoystickHandler;
            if (rightTrigger) {
                handler.onRightTriggerKeyStateChanged(deviceId, down, event.getEventTime());
            }
            // Some Android handheld/controller drivers expose L2/R2 as key edges
            // without a matching final MotionEvent. When a controller mod owns SDL,
            // mirror that edge into SDL's canonical trigger axis slots as well.
            if (false) {
                handler.onTriggerKeyAxisChanged(deviceId, leftTrigger, down);
            }
        }

        return rightTrigger
                && changed
                && allowVanillaRightTriggerMouseRoute
                && routeRightTriggerAsMouse(deviceId, down, "key");
    }

    static boolean isRightTriggerKeyDown(int deviceId) {
        synchronized (RIGHT_TRIGGER_KEY_LOCK) {
            RightTriggerKeyState state = RIGHT_TRIGGER_KEYS.get(deviceId);
            return state != null && state.down;
        }
    }

    static long getRightTriggerKeyChangedAtMs(int deviceId) {
        synchronized (RIGHT_TRIGGER_KEY_LOCK) {
            RightTriggerKeyState state = RIGHT_TRIGGER_KEYS.get(deviceId);
            return state != null ? state.changedAtMs : 0L;
        }
    }

    public static void initialize() {
        if (mJoystickHandler == null) {
            if (Build.VERSION.SDK_INT >= 19 /* Android 4.4 (KITKAT) */) {
                mJoystickHandler = new SDLJoystickHandler_API19();
            } else {
                mJoystickHandler = new SDLJoystickHandler_API16();
            }
        }

        if (mHapticHandler == null) {
            if (Build.VERSION.SDK_INT >= 31 /* Android 12.0 (S) */) {
                mHapticHandler = new SDLHapticHandler_API31();
            } else if (Build.VERSION.SDK_INT >= 26 /* Android 8.0 (O) */) {
                mHapticHandler = new SDLHapticHandler_API26();
            } else {
                mHapticHandler = new SDLHapticHandler();
            }
        }
    }

    // Joystick glue code, just a series of stubs that redirect to the SDLJoystickHandler instance
    public static boolean handleJoystickMotionEvent(MotionEvent event) {
        if (mJoystickHandler == null) {
            initialize();
        }
        return mJoystickHandler != null && mJoystickHandler.handleMotionEvent(event);
    }

    /**
     * This method is called by SDL using JNI.
     */
    public static void pollInputDevices() {
        if (mJoystickHandler == null) {
            initialize();
        }
        if (mJoystickHandler != null) {
            mJoystickHandler.pollInputDevices();
        }
    }

    /**
     * This method is called by SDL using JNI.
     */
    public static void pollHapticDevices() {
        if (mHapticHandler == null) {
            initialize();
        }
        if (mHapticHandler != null) {
            mHapticHandler.pollHapticDevices();
        }
    }

    /**
     * This method is called by SDL using JNI.
     */
    public static void hapticRun(int device_id, float intensity, int length) {
        mHapticHandler.run(device_id, intensity, length);
    }

    /**
     * This method is called by SDL using JNI.
     */
    public static void hapticRumble(int device_id, float low_frequency_intensity, float high_frequency_intensity, int length) {
        mHapticHandler.rumble(device_id, low_frequency_intensity, high_frequency_intensity, length);
    }

    /**
     * This method is called by SDL using JNI.
     */
    public static void hapticStop(int device_id)
    {
        mHapticHandler.stop(device_id);
    }

    // Check if a given device is considered a possible SDL joystick
    public static boolean isDeviceSDLJoystick(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        // We cannot use InputDevice.isVirtual before API 16, so let's accept
        // only nonnegative device ids (VIRTUAL_KEYBOARD equals -1)
        if ((device == null) || (deviceId < 0)) {
            return false;
        }
        int sources = device.getSources();

        /* This is called for every button press, so let's not spam the logs */
        /*
        if ((sources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            Log.v(TAG, "Input device " + device.getName() + " has class joystick.");
        }
        if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
            Log.v(TAG, "Input device " + device.getName() + " is a dpad.");
        }
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            Log.v(TAG, "Input device " + device.getName() + " is a gamepad.");
        }
        */

        return ((sources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 ||
                ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) ||
                ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
        );
    }
}

class SDLJoystickHandler {

    /**
     * Handles given MotionEvent.
     * @param event the event to be handled.
     * @return if given event was processed.
     */
    public boolean handleMotionEvent(MotionEvent event) {
        return false;
    }

    /**
     * Handles adding and removing of input devices.
     */
    public void pollInputDevices() {
    }
}

/* Actual joystick functionality available for API >= 12 devices */
class SDLJoystickHandler_API16 extends SDLJoystickHandler {

    private static final String TAG = "SDLControllerManager";

    static class SDLJoystick {
        public int device_id;
        public String name;
        public String desc;
        public ArrayList<InputDevice.MotionRange> axes;
        public ArrayList<InputDevice.MotionRange> hats;

        // Some Android controller drivers mirror the right-trigger value onto a
        // right-stick axis for one or more MotionEvents. SDL interprets that as a
        // full camera deflection, which can turn the player around when attacking.
        public float lastRightX;
        public float lastRightY;
        public float lastRawRightX;
        public float lastRawRightY;
        public float lastRightTrigger = -1.0f;
        public boolean lastRightTriggerPressed;
        public long triggerGuardUntilMs;
        public float triggerGuardBaselineX;
        public float triggerGuardBaselineY;
        public long lastSeenTriggerKeyChangeMs;
        public boolean suppressRightXFromTrigger;
        public boolean suppressRightYFromTrigger;
        public float suppressedRightXRaw;
        public float suppressedRightYRaw;
        public float suppressedRightXBaseline;
        public float suppressedRightYBaseline;
        public boolean loggedTriggerMirrorSuppression;
        public boolean directRightTriggerPressed;
        public float lastDirectRightTriggerAmount;
        public long hardTriggerGuardUntilMs;
    }
    static class RangeComparator implements Comparator<InputDevice.MotionRange> {
        @Override
        public int compare(InputDevice.MotionRange arg0, InputDevice.MotionRange arg1) {
            // Some controllers, like the Moga Pro 2, return AXIS_GAS (22) for right trigger and AXIS_BRAKE (23) for left trigger - swap them so they're sorted in the right order for SDL
            int arg0Axis = arg0.getAxis();
            int arg1Axis = arg1.getAxis();
            if (arg0Axis == MotionEvent.AXIS_GAS) {
                arg0Axis = MotionEvent.AXIS_BRAKE;
            } else if (arg0Axis == MotionEvent.AXIS_BRAKE) {
                arg0Axis = MotionEvent.AXIS_GAS;
            }
            if (arg1Axis == MotionEvent.AXIS_GAS) {
                arg1Axis = MotionEvent.AXIS_BRAKE;
            } else if (arg1Axis == MotionEvent.AXIS_BRAKE) {
                arg1Axis = MotionEvent.AXIS_GAS;
            }

            // Make sure the AXIS_Z is sorted between AXIS_RY and AXIS_RZ.
            // This is because the usual pairing are:
            // - AXIS_X + AXIS_Y (left stick).
            // - AXIS_RX, AXIS_RY (sometimes the right stick, sometimes triggers).
            // - AXIS_Z, AXIS_RZ (sometimes the right stick, sometimes triggers).
            // This sorts the axes in the above order, which tends to be correct
            // for Xbox-ish game pads that have the right stick on RX/RY and the
            // triggers on Z/RZ.
            //
            // Gamepads that don't have AXIS_Z/AXIS_RZ but use
            // AXIS_LTRIGGER/AXIS_RTRIGGER are unaffected by this.
            //
            // References:
            // - https://developer.android.com/develop/ui/views/touch-and-input/game-controllers/controller-input
            // - https://www.kernel.org/doc/html/latest/input/gamepad.html
            if (arg0Axis == MotionEvent.AXIS_Z) {
                arg0Axis = MotionEvent.AXIS_RZ - 1;
            } else if (arg0Axis > MotionEvent.AXIS_Z && arg0Axis < MotionEvent.AXIS_RZ) {
                --arg0Axis;
            }
            if (arg1Axis == MotionEvent.AXIS_Z) {
                arg1Axis = MotionEvent.AXIS_RZ - 1;
            } else if (arg1Axis > MotionEvent.AXIS_Z && arg1Axis < MotionEvent.AXIS_RZ) {
                --arg1Axis;
            }

            return arg0Axis - arg1Axis;
        }
    }

    private final ArrayList<SDLJoystick> mJoysticks;

    public SDLJoystickHandler_API16() {

        mJoysticks = new ArrayList<SDLJoystick>();
    }

    private static InputDevice.MotionRange findAxisRange(
            List<InputDevice.MotionRange> ranges,
            int axis) {
        if (ranges == null) return null;
        for (InputDevice.MotionRange range : ranges) {
            if (range == null) continue;
            if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) continue;
            if (range.getAxis() == axis) return range;
        }
        return null;
    }

    private static boolean isCenteredAxis(InputDevice.MotionRange range) {
        return range != null && range.getMin() < -0.25f && range.getMax() > 0.25f;
    }

    private static boolean isPositiveAxis(InputDevice.MotionRange range) {
        return range != null && range.getMin() >= -0.10f && range.getMax() > 0.50f;
    }

    private static void addAxisUnique(
            ArrayList<InputDevice.MotionRange> output,
            InputDevice.MotionRange range) {
        if (range == null) return;
        for (InputDevice.MotionRange existing : output) {
            if (existing.getAxis() == range.getAxis()) return;
        }
        output.add(range);
    }

    private static InputDevice.MotionRange firstUsableTrigger(
            List<InputDevice.MotionRange> ranges,
            int primary,
            int fallback) {
        InputDevice.MotionRange first = findAxisRange(ranges, primary);
        InputDevice.MotionRange second = findAxisRange(ranges, fallback);
        if (isPositiveAxis(first)) return first;
        if (isPositiveAxis(second)) return second;
        return first != null ? first : second;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float positiveAxisAmount(
            MotionEvent event,
            InputDevice device,
            int pointerIndex,
            int axis) {
        if (event == null) return 0.0f;
        float raw = event.getAxisValue(axis, pointerIndex);
        InputDevice.MotionRange range = null;
        if (device != null) {
            range = findAxisRange(device.getMotionRanges(), axis);
        }
        if (range == null) {
            return clamp01(raw);
        }
        float span = range.getRange();
        if (span <= 0.0001f) return 0.0f;
        return clamp01((raw - range.getMin()) / span);
    }

    private static float directRightTriggerAmount(
            MotionEvent event,
            int pointerIndex) {
        InputDevice device = event != null ? event.getDevice() : null;
        float rTrigger = positiveAxisAmount(
                event, device, pointerIndex, MotionEvent.AXIS_RTRIGGER);
        float gas = positiveAxisAmount(
                event, device, pointerIndex, MotionEvent.AXIS_GAS);
        return Math.max(rTrigger, gas);
    }

    private static float triggerAmountFromCanonicalAxes(
            SDLJoystick joystick,
            float[] values) {
        float amount = 0.0f;
        if (joystick == null || joystick.axes == null || values == null) return amount;

        int count = Math.min(joystick.axes.size(), values.length);
        for (int i = 0; i < count; i++) {
            int axis = joystick.axes.get(i).getAxis();
            if (axis == MotionEvent.AXIS_RTRIGGER || axis == MotionEvent.AXIS_GAS) {
                amount = Math.max(amount, clamp01((values[i] + 1.0f) * 0.5f));
            }
        }

        // Preserve compatibility with SDL's canonical first-six-axis contract
        // for controllers whose R2 is exposed on a legacy fallback axis.
        if (values.length > 5) {
            amount = Math.max(amount, clamp01((values[5] + 1.0f) * 0.5f));
        }
        return amount;
    }

    private static boolean looksLikeTriggerMirror(float axis, float triggerAmount) {
        if (triggerAmount < 0.12f || Math.abs(axis) < 0.42f) return false;
        float signedTrigger = triggerAmount * 2.0f - 1.0f;
        return Math.abs(axis - signedTrigger) < 0.22f
                || Math.abs(axis + signedTrigger) < 0.22f
                || (triggerAmount > 0.82f && Math.abs(axis) > 0.82f);
    }

    /**
     * SDL's Android native side treats the first six Java axes as
     * leftX/leftY/rightX/rightY/leftTrigger/rightTrigger. Sorting Android axis
     * numbers is not enough: many pads expose both Z/RZ and RX/RY, with one pair
     * being triggers. That can place a trigger in the right-stick slot and cause
     * a full 180/360-degree camera turn when R2 is pressed.
     */
    private static ArrayList<InputDevice.MotionRange> buildCanonicalAxes(
            InputDevice device,
            List<InputDevice.MotionRange> ranges) {
        ArrayList<InputDevice.MotionRange> canonical = new ArrayList<InputDevice.MotionRange>();

        InputDevice.MotionRange x = findAxisRange(ranges, MotionEvent.AXIS_X);
        InputDevice.MotionRange y = findAxisRange(ranges, MotionEvent.AXIS_Y);
        InputDevice.MotionRange z = findAxisRange(ranges, MotionEvent.AXIS_Z);
        InputDevice.MotionRange rz = findAxisRange(ranges, MotionEvent.AXIS_RZ);
        InputDevice.MotionRange rx = findAxisRange(ranges, MotionEvent.AXIS_RX);
        InputDevice.MotionRange ry = findAxisRange(ranges, MotionEvent.AXIS_RY);

        addAxisUnique(canonical, x);
        addAxisUnique(canonical, y);

        boolean zrCentered = isCenteredAxis(z) && isCenteredAxis(rz);
        boolean rxryCentered = isCenteredAxis(rx) && isCenteredAxis(ry);
        boolean useZrForRightStick;
        if (zrCentered != rxryCentered) {
            useZrForRightStick = zrCentered;
        } else if (zrCentered) {
            // Android's standard Xbox layout uses Z/RZ for the right stick.
            useZrForRightStick = true;
        } else {
            // Fall back to the most complete pair without promoting a positive
            // trigger range into a stick slot when another pair exists.
            useZrForRightStick = z != null && rz != null
                    && !(isPositiveAxis(z) || isPositiveAxis(rz));
        }

        InputDevice.MotionRange rightX = useZrForRightStick ? z : rx;
        InputDevice.MotionRange rightY = useZrForRightStick ? rz : ry;
        if (rightX == null || rightY == null) {
            InputDevice.MotionRange altX = useZrForRightStick ? rx : z;
            InputDevice.MotionRange altY = useZrForRightStick ? ry : rz;
            if (rightX == null) rightX = altX;
            if (rightY == null) rightY = altY;
        }
        addAxisUnique(canonical, rightX);
        addAxisUnique(canonical, rightY);

        InputDevice.MotionRange leftTrigger = firstUsableTrigger(
                ranges, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE);
        InputDevice.MotionRange rightTrigger = firstUsableTrigger(
                ranges, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS);

        // Some older pads expose triggers on the non-stick RX/RY or Z/RZ pair.
        if (leftTrigger == null || rightTrigger == null) {
            InputDevice.MotionRange fallbackLeft = useZrForRightStick ? rx : z;
            InputDevice.MotionRange fallbackRight = useZrForRightStick ? ry : rz;
            if (leftTrigger == null && fallbackLeft != rightX && fallbackLeft != rightY) {
                leftTrigger = fallbackLeft;
            }
            if (rightTrigger == null && fallbackRight != rightX && fallbackRight != rightY) {
                rightTrigger = fallbackRight;
            }
        }
        addAxisUnique(canonical, leftTrigger);
        addAxisUnique(canonical, rightTrigger);

        ArrayList<InputDevice.MotionRange> remaining = new ArrayList<InputDevice.MotionRange>();
        if (ranges != null) {
            for (InputDevice.MotionRange range : ranges) {
                if (range == null) continue;
                if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) continue;
                if (range.getAxis() == MotionEvent.AXIS_HAT_X
                        || range.getAxis() == MotionEvent.AXIS_HAT_Y) continue;
                remaining.add(range);
            }
        }
        Collections.sort(remaining, new RangeComparator());
        for (InputDevice.MotionRange range : remaining) addAxisUnique(canonical, range);

        StringBuilder mapping = new StringBuilder();
        for (int i = 0; i < canonical.size(); i++) {
            if (i > 0) mapping.append(',');
            mapping.append(i).append(':')
                    .append(MotionEvent.axisToString(canonical.get(i).getAxis()));
        }
        Log.i(TAG, "Turtle canonical SDL axes device="
                + (device != null ? device.getName() : "<unknown>")
                + " mapping=" + mapping);
        return canonical;
    }

    private static boolean firstPollDone = false;
    @Override
    public void pollInputDevices() {
        if (!firstPollDone) {
            Log.i("SDL", "SDL input device poll started");
            firstPollDone = true;
        }

        // When called from a HotSpot JVM thread (e.g. Minecraft's Render thread), Looper is null.
        // We cannot call nativeAddJoystick/nativeRemoveJoystick directly from that thread because
        // the JNI transition causes a TLS key collision between ART and HotSpot, corrupting the
        // cached JNIEnv and crashing in SDL_UpdateJoysticks.
        // Instead, post those JNI calls to the Android main thread where ART is always safe.
        // We still track the joystick in mJoysticks immediately so duplicate polls don't re-post.
        final boolean hasLooper = Looper.myLooper() != null;

        int[] deviceIds = InputDevice.getDeviceIds();

        for (int device_id : deviceIds) {
            if (SDLControllerManager.isDeviceSDLJoystick(device_id)) {
                SDLJoystick joystick = getJoystick(device_id);
                if (joystick == null) {
                    InputDevice joystickDevice = InputDevice.getDevice(device_id);
                    joystick = new SDLJoystick();
                    joystick.device_id = device_id;
                    joystick.name = joystickDevice.getName();
                    joystick.desc = getJoystickDescriptor(joystickDevice);
                    joystick.axes = new ArrayList<InputDevice.MotionRange>();
                    joystick.hats = new ArrayList<InputDevice.MotionRange>();

                    List<InputDevice.MotionRange> ranges = joystickDevice.getMotionRanges();
                    joystick.axes = buildCanonicalAxes(joystickDevice, ranges);
                    ArrayList<InputDevice.MotionRange> sortedHats = new ArrayList<InputDevice.MotionRange>();
                    for (InputDevice.MotionRange range : ranges) {
                        if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                                && (range.getAxis() == MotionEvent.AXIS_HAT_X
                                || range.getAxis() == MotionEvent.AXIS_HAT_Y)) {
                            sortedHats.add(range);
                        }
                    }
                    Collections.sort(sortedHats, new RangeComparator());
                    joystick.hats.addAll(sortedHats);

                    boolean can_rumble = false;
                    if (Build.VERSION.SDK_INT >= 31 /* Android 12.0 (S) */) {
                        VibratorManager manager = joystickDevice.getVibratorManager();
                        int[] vibrators = manager.getVibratorIds();
                        if (vibrators.length > 0) {
                            can_rumble = true;
                        }
                    }

                    // Add to tracking list now so subsequent polls (from the same Render thread)
                    // see this joystick as already-known and don't post a duplicate.
                    mJoysticks.add(joystick);

                    final int fDeviceId = joystick.device_id;
                    final String fName = joystick.name;
                    final String fDesc = joystick.desc;
                    final int fVendorId = getVendorId(joystickDevice);
                    final int fProductId = getProductId(joystickDevice);
                    final int fButtonMask = getButtonMask(joystickDevice);
                    final int fAxesSize = joystick.axes.size();
                    final int fAxisMask = getAxisMask(joystick.axes);
                    final int fHatsSize = joystick.hats.size() / 2;
                    final boolean fCanRumble = can_rumble;

                    if (hasLooper) {
                        SDLControllerManager.nativeAddJoystick(fDeviceId, fName, fDesc,
                                fVendorId, fProductId, fButtonMask, fAxesSize, fAxisMask, fHatsSize, fCanRumble);
                    } else {
                        // Post to main thread: safe ART context, no TLS collision risk
                        new Handler(Looper.getMainLooper()).post(() ->
                                SDLControllerManager.nativeAddJoystick(fDeviceId, fName, fDesc,
                                        fVendorId, fProductId, fButtonMask, fAxesSize, fAxisMask, fHatsSize, fCanRumble)
                        );
                    }
                }
            }
        }

        /* Check removed devices */
        ArrayList<Integer> removedDevices = null;
        for (SDLJoystick joystick : mJoysticks) {
            int device_id = joystick.device_id;
            int i;
            for (i = 0; i < deviceIds.length; i++) {
                if (device_id == deviceIds[i]) break;
            }
            if (i == deviceIds.length) {
                if (removedDevices == null) {
                    removedDevices = new ArrayList<Integer>();
                }
                removedDevices.add(device_id);
            }
        }

        if (removedDevices != null) {
            for (int device_id : removedDevices) {
                // Remove from tracking immediately so the next poll doesn't re-add
                for (int i = 0; i < mJoysticks.size(); i++) {
                    if (mJoysticks.get(i).device_id == device_id) {
                        mJoysticks.remove(i);
                        break;
                    }
                }
                if (hasLooper) {
                    SDLControllerManager.nativeRemoveJoystick(device_id);
                } else {
                    final int fRemoveId = device_id;
                    new Handler(Looper.getMainLooper()).post(() ->
                            SDLControllerManager.nativeRemoveJoystick(fRemoveId)
                    );
                }
            }
        }
    }

    protected SDLJoystick getJoystick(int device_id) {
        for (SDLJoystick joystick : mJoysticks) {
            if (joystick.device_id == device_id) {
                return joystick;
            }
        }
        return null;
    }

    void onRightTriggerKeyStateChanged(int deviceId, boolean down, long eventTimeMs) {
        SDLJoystick joystick = getJoystick(deviceId);
        if (joystick == null) return;
        if (down) {
            joystick.triggerGuardBaselineX = joystick.lastRightX;
            joystick.triggerGuardBaselineY = joystick.lastRightY;
            joystick.triggerGuardUntilMs = Math.max(
                    joystick.triggerGuardUntilMs, eventTimeMs + 420L);
            joystick.hardTriggerGuardUntilMs = Math.max(
                    joystick.hardTriggerGuardUntilMs, eventTimeMs + 180L);
            joystick.loggedTriggerMirrorSuppression = false;
        } else {
            // A key-only trigger may not emit a final joystick MotionEvent. Clear
            // any held clamp here so the next R2 press starts from a clean state.
            joystick.suppressRightXFromTrigger = false;
            joystick.suppressRightYFromTrigger = false;
            joystick.lastRightTriggerPressed = false;
            joystick.triggerGuardUntilMs = 0L;
            joystick.hardTriggerGuardUntilMs = 0L;
        }
    }


    void onTriggerKeyAxisChanged(int deviceId, boolean leftTrigger, boolean down) {
        SDLJoystick joystick = getJoystick(deviceId);
        if (joystick == null) return;
        int axisIndex = leftTrigger ? 4 : 5;
        if (joystick.axes == null || joystick.axes.size() <= axisIndex) return;
        try {
            SDLControllerManager.onNativeJoy(
                    joystick.device_id,
                    axisIndex,
                    down ? 1.0f : -1.0f);
            System.out.println("TurtleSDLController: synthesized "
                    + (leftTrigger ? "L2" : "R2")
                    + " axis from Android key edge down=" + down
                    + " deviceId=" + deviceId);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to synthesize SDL trigger axis from key event", throwable);
        }
    }

    @Override
    public boolean handleMotionEvent(MotionEvent event) {
        int actionPointerIndex = event.getActionIndex();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            SDLJoystick joystick = getJoystick(event.getDeviceId());
            if (joystick != null) {
                float[] values = new float[joystick.axes.size()];
                for (int i = 0; i < joystick.axes.size(); i++) {
                    InputDevice.MotionRange range = joystick.axes.get(i);
                    float span = range.getRange();
                    if (span <= 0.0001f) {
                        values[i] = 0.0f;
                    } else {
                        /* Normalize the value to -1...1 */
                        values[i] = (event.getAxisValue(range.getAxis(), actionPointerIndex)
                                - range.getMin()) / span * 2.0f - 1.0f;
                        values[i] = Math.max(-1.0f, Math.min(1.0f, values[i]));
                    }
                }

                float directTriggerAmount = directRightTriggerAmount(
                        event, actionPointerIndex);
                float routeTriggerAmount = Math.max(
                        directTriggerAmount,
                        triggerAmountFromCanonicalAxes(joystick, values));
                boolean directTriggerPressed = joystick.directRightTriggerPressed
                        ? routeTriggerAmount > 0.08f
                        : routeTriggerAmount > 0.18f;
                boolean directTriggerEdge = directTriggerPressed
                        && !joystick.directRightTriggerPressed;
                boolean controllerModOwnsSdl = false;
                if (!controllerModOwnsSdl
                        && directTriggerPressed != joystick.directRightTriggerPressed) {
                    SDLControllerManager.routeRightTriggerAsMouse(
                            joystick.device_id, directTriggerPressed, "axis");
                }
                joystick.directRightTriggerPressed = directTriggerPressed;
                joystick.lastDirectRightTriggerAmount = routeTriggerAmount;

                // Never pass the physical R2 axis into SDL's gamepad axis stream
                // only for vanilla Turtle input. Controller mods need both
                // trigger axes intact for their own mappings.
                // Snapshot 4 receives the same holdable action as a motion-free left
                // mouse button above, which avoids both duplicate attacks and the SDL3
                // trigger/right-stick alias that causes the pitch snap.
                if (!controllerModOwnsSdl) {
                    for (int i = 0; i < joystick.axes.size(); i++) {
                        int axis = joystick.axes.get(i).getAxis();
                        if (axis == MotionEvent.AXIS_RTRIGGER
                                || axis == MotionEvent.AXIS_GAS) {
                            values[i] = -1.0f;
                        }
                    }
                    if (values.length > 5) {
                        // SDL's Android gamepad contract reserves slot 5 for R2 even
                        // when the Android driver uses a legacy fallback axis name.
                        values[5] = -1.0f;
                    }
                }

                if (!controllerModOwnsSdl && values.length >= 4) {
                    long eventTimeMs = event.getEventTime();
                    float rightX = values[2];
                    float rightY = values[3];
                    float triggerAmount = routeTriggerAmount;
                    boolean triggerKeyDown = SDLControllerManager.isRightTriggerKeyDown(
                            joystick.device_id);
                    long triggerKeyChangeMs = SDLControllerManager.getRightTriggerKeyChangedAtMs(
                            joystick.device_id);
                    float previousTriggerAmount = clamp01(
                            (joystick.lastRightTrigger + 1.0f) * 0.5f);
                    boolean triggerPressed = triggerKeyDown || triggerAmount > 0.12f;
                    boolean keyEdge = triggerKeyChangeMs > joystick.lastSeenTriggerKeyChangeMs
                            && triggerKeyDown;
                    boolean axisEdge = triggerAmount - previousTriggerAmount > 0.10f
                            && triggerAmount > 0.18f;
                    boolean triggerPressedEdge = (triggerPressed
                            && !joystick.lastRightTriggerPressed) || keyEdge || axisEdge
                            || directTriggerEdge;
                    boolean triggerReleased = !triggerPressed;

                    if (triggerKeyChangeMs > joystick.lastSeenTriggerKeyChangeMs) {
                        joystick.lastSeenTriggerKeyChangeMs = triggerKeyChangeMs;
                    }
                    if (triggerPressedEdge) {
                        joystick.triggerGuardBaselineX = joystick.lastRightX;
                        joystick.triggerGuardBaselineY = joystick.lastRightY;
                        joystick.triggerGuardUntilMs = eventTimeMs + 420L;
                        joystick.hardTriggerGuardUntilMs = eventTimeMs + 180L;
                        joystick.loggedTriggerMirrorSuppression = false;
                    }

                    boolean triggerGuardActive = triggerPressed
                            && eventTimeMs <= joystick.triggerGuardUntilMs;
                    float comparisonRightX = triggerGuardActive
                            ? joystick.triggerGuardBaselineX : joystick.lastRightX;
                    float comparisonRightY = triggerGuardActive
                            ? joystick.triggerGuardBaselineY : joystick.lastRightY;
                    float rightXJump = Math.abs(rightX - comparisonRightX);
                    float rightYJump = Math.abs(rightY - comparisonRightY);
                    float rawXJump = Math.abs(rightX - joystick.lastRawRightX);
                    float rawYJump = Math.abs(rightY - joystick.lastRawRightY);

                    if (directTriggerPressed
                            && eventTimeMs <= joystick.hardTriggerGuardUntilMs) {
                        values[2] = joystick.triggerGuardBaselineX;
                        values[3] = joystick.triggerGuardBaselineY;
                        rightX = values[2];
                        rightY = values[3];
                        rightXJump = 0.0f;
                        rightYJump = 0.0f;
                        rawXJump = 0.0f;
                        rawYJump = 0.0f;
                    }

                    boolean suspiciousX = triggerGuardActive
                            && Math.abs(rightX) > 0.78f
                            && rightXJump > 0.62f
                            && rawXJump > 0.52f;
                    boolean suspiciousY = triggerGuardActive
                            && Math.abs(rightY) > 0.78f
                            && rightYJump > 0.62f
                            && rawYJump > 0.52f;

                    // A delayed mirrored sample can arrive after the initial edge
                    // event. Correlation with the trigger keeps detection reliable
                    // even when the driver uses a different axis normalization.
                    suspiciousX |= triggerGuardActive
                            && rightXJump > 0.28f
                            && looksLikeTriggerMirror(rightX, triggerAmount);
                    suspiciousY |= triggerGuardActive
                            && rightYJump > 0.28f
                            && looksLikeTriggerMirror(rightY, triggerAmount);

                    if (suspiciousX && !joystick.suppressRightXFromTrigger) {
                        joystick.suppressRightXFromTrigger = true;
                        joystick.suppressedRightXRaw = rightX;
                        joystick.suppressedRightXBaseline = joystick.triggerGuardBaselineX;
                    }
                    if (suspiciousY && !joystick.suppressRightYFromTrigger) {
                        joystick.suppressRightYFromTrigger = true;
                        joystick.suppressedRightYRaw = rightY;
                        joystick.suppressedRightYBaseline = joystick.triggerGuardBaselineY;
                    }

                    if ((suspiciousX || suspiciousY)
                            && !joystick.loggedTriggerMirrorSuppression) {
                        joystick.loggedTriggerMirrorSuppression = true;
                        String message = "Turtle suppressed delayed R2 camera fling device="
                                + joystick.name
                                + " x=" + rightX
                                + " y=" + rightY
                                + " trigger=" + triggerAmount
                                + " keyDown=" + triggerKeyDown
                                + " suppressX=" + suspiciousX
                                + " suppressY=" + suspiciousY;
                        Log.i(TAG, message);
                        System.out.println("TurtleSDL3Controller: " + message);
                    }

                    if (joystick.suppressRightXFromTrigger) {
                        boolean backAtBaseline = Math.abs(
                                rightX - joystick.suppressedRightXBaseline) < 0.18f;
                        boolean stillLooksMirrored = looksLikeTriggerMirror(
                                rightX, triggerAmount)
                                || Math.abs(rightX - joystick.suppressedRightXRaw) < 0.24f;
                        boolean deliberateMove = eventTimeMs > joystick.triggerGuardUntilMs
                                && !stillLooksMirrored
                                && Math.abs(rightX - joystick.suppressedRightXRaw) > 0.28f;
                        if (triggerReleased || backAtBaseline || deliberateMove) {
                            joystick.suppressRightXFromTrigger = false;
                        } else {
                            values[2] = joystick.suppressedRightXBaseline;
                        }
                    }
                    if (joystick.suppressRightYFromTrigger) {
                        boolean backAtBaseline = Math.abs(
                                rightY - joystick.suppressedRightYBaseline) < 0.18f;
                        boolean stillLooksMirrored = looksLikeTriggerMirror(
                                rightY, triggerAmount)
                                || Math.abs(rightY - joystick.suppressedRightYRaw) < 0.24f;
                        boolean deliberateMove = eventTimeMs > joystick.triggerGuardUntilMs
                                && !stillLooksMirrored
                                && Math.abs(rightY - joystick.suppressedRightYRaw) > 0.28f;
                        if (triggerReleased || backAtBaseline || deliberateMove) {
                            joystick.suppressRightYFromTrigger = false;
                        } else {
                            values[3] = joystick.suppressedRightYBaseline;
                        }
                    }

                    joystick.lastRawRightX = rightX;
                    joystick.lastRawRightY = rightY;
                    joystick.lastRightX = values[2];
                    joystick.lastRightY = values[3];
                    joystick.lastRightTrigger = triggerAmount * 2.0f - 1.0f;
                    joystick.lastRightTriggerPressed = triggerPressed;
                } else if (values.length >= 4) {
                    // Controller mods receive the canonical SDL axes unmodified.
                    // Keep local history updated without applying the vanilla
                    // R2-to-mouse or camera-clamp workaround.
                    joystick.lastRawRightX = values[2];
                    joystick.lastRawRightY = values[3];
                    joystick.lastRightX = values[2];
                    joystick.lastRightY = values[3];
                    joystick.lastRightTrigger = routeTriggerAmount * 2.0f - 1.0f;
                    joystick.lastRightTriggerPressed = directTriggerPressed;
                }

                for (int i = 0; i < values.length; i++) {
                    SDLControllerManager.onNativeJoy(joystick.device_id, i, values[i]);
                }
                for (int i = 0; i < joystick.hats.size() / 2; i++) {
                    int hatX = Math.round(event.getAxisValue(joystick.hats.get(2 * i).getAxis(), actionPointerIndex));
                    int hatY = Math.round(event.getAxisValue(joystick.hats.get(2 * i + 1).getAxis(), actionPointerIndex));
                    SDLControllerManager.onNativeHat(joystick.device_id, i, hatX, hatY);
                }
            }
        }
        return true;
    }

    public String getJoystickDescriptor(InputDevice joystickDevice) {
        String desc = joystickDevice.getDescriptor();

        if (desc != null && !desc.isEmpty()) {
            return desc;
        }

        return joystickDevice.getName();
    }
    public int getProductId(InputDevice joystickDevice) {
        return 0;
    }
    public int getVendorId(InputDevice joystickDevice) {
        return 0;
    }
    public int getAxisMask(List<InputDevice.MotionRange> ranges) {
        return -1;
    }
    public int getButtonMask(InputDevice joystickDevice) {
        return -1;
    }
}

class SDLJoystickHandler_API19 extends SDLJoystickHandler_API16 {

    @Override
    public int getProductId(InputDevice joystickDevice) {
        return joystickDevice.getProductId();
    }

    @Override
    public int getVendorId(InputDevice joystickDevice) {
        return joystickDevice.getVendorId();
    }

    @Override
    public int getAxisMask(List<InputDevice.MotionRange> ranges) {
        // For compatibility, keep computing the axis mask like before,
        // only really distinguishing 2, 4 and 6 axes.
        int axis_mask = 0;
        if (ranges.size() >= 2) {
            // ((1 << SDL_GAMEPAD_AXIS_LEFTX) | (1 << SDL_GAMEPAD_AXIS_LEFTY))
            axis_mask |= 0x0003;
        }
        if (ranges.size() >= 4) {
            // ((1 << SDL_GAMEPAD_AXIS_RIGHTX) | (1 << SDL_GAMEPAD_AXIS_RIGHTY))
            axis_mask |= 0x000c;
        }
        if (ranges.size() >= 6) {
            // ((1 << SDL_GAMEPAD_AXIS_LEFT_TRIGGER) | (1 << SDL_GAMEPAD_AXIS_RIGHT_TRIGGER))
            axis_mask |= 0x0030;
        }
        // Also add an indicator bit for whether the sorting order has changed.
        // This serves to disable outdated gamecontrollerdb.txt mappings.
        boolean have_z = false;
        boolean have_past_z_before_rz = false;
        for (InputDevice.MotionRange range : ranges) {
            int axis = range.getAxis();
            if (axis == MotionEvent.AXIS_Z) {
                have_z = true;
            } else if (axis > MotionEvent.AXIS_Z && axis < MotionEvent.AXIS_RZ) {
                have_past_z_before_rz = true;
            }
        }
        if (have_z && have_past_z_before_rz) {
            // If both these exist, the compare() function changed sorting order.
            // Set a bit to indicate this fact.
            axis_mask |= 0x8000;
        }
        return axis_mask;
    }

    @Override
    public int getButtonMask(InputDevice joystickDevice) {
        int button_mask = 0;
        int[] keys = new int[] {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_DPAD_CENTER,

                // These don't map into any SDL controller buttons directly
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_C,
                KeyEvent.KEYCODE_BUTTON_Z,
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_BUTTON_4,
                KeyEvent.KEYCODE_BUTTON_5,
                KeyEvent.KEYCODE_BUTTON_6,
                KeyEvent.KEYCODE_BUTTON_7,
                KeyEvent.KEYCODE_BUTTON_8,
                KeyEvent.KEYCODE_BUTTON_9,
                KeyEvent.KEYCODE_BUTTON_10,
                KeyEvent.KEYCODE_BUTTON_11,
                KeyEvent.KEYCODE_BUTTON_12,
                KeyEvent.KEYCODE_BUTTON_13,
                KeyEvent.KEYCODE_BUTTON_14,
                KeyEvent.KEYCODE_BUTTON_15,
                KeyEvent.KEYCODE_BUTTON_16,
        };
        int[] masks = new int[] {
                (1 << 0),   // A -> A
                (1 << 1),   // B -> B
                (1 << 2),   // X -> X
                (1 << 3),   // Y -> Y
                (1 << 4),   // BACK -> BACK
                (1 << 6),   // MENU -> START
                (1 << 5),   // MODE -> GUIDE
                (1 << 6),   // START -> START
                (1 << 7),   // THUMBL -> LEFTSTICK
                (1 << 8),   // THUMBR -> RIGHTSTICK
                (1 << 9),   // L1 -> LEFTSHOULDER
                (1 << 10),  // R1 -> RIGHTSHOULDER
                (1 << 11),  // DPAD_UP -> DPAD_UP
                (1 << 12),  // DPAD_DOWN -> DPAD_DOWN
                (1 << 13),  // DPAD_LEFT -> DPAD_LEFT
                (1 << 14),  // DPAD_RIGHT -> DPAD_RIGHT
                (1 << 4),   // SELECT -> BACK
                (1 << 0),   // DPAD_CENTER -> A
                (1 << 15),  // L2 -> ??
                (1 << 16),  // R2 -> ??
                (1 << 17),  // C -> ??
                (1 << 18),  // Z -> ??
                (1 << 20),  // 1 -> ??
                (1 << 21),  // 2 -> ??
                (1 << 22),  // 3 -> ??
                (1 << 23),  // 4 -> ??
                (1 << 24),  // 5 -> ??
                (1 << 25),  // 6 -> ??
                (1 << 26),  // 7 -> ??
                (1 << 27),  // 8 -> ??
                (1 << 28),  // 9 -> ??
                (1 << 29),  // 10 -> ??
                (1 << 30),  // 11 -> ??
                (1 << 31),  // 12 -> ??
                // We're out of room...
                0xFFFFFFFF,  // 13 -> ??
                0xFFFFFFFF,  // 14 -> ??
                0xFFFFFFFF,  // 15 -> ??
                0xFFFFFFFF,  // 16 -> ??
        };
        boolean[] has_keys = joystickDevice.hasKeys(keys);
        for (int i = 0; i < keys.length; ++i) {
            if (has_keys[i]) {
                button_mask |= masks[i];
            }
        }
        return button_mask;
    }
}

class SDLHapticHandler_API31 extends SDLHapticHandler {
    @Override
    public void run(int device_id, float intensity, int length) {
        SDLHaptic haptic = getHaptic(device_id);
        if (haptic != null) {
            vibrate(haptic.vib, intensity, length);
        }
    }

    @Override
    public void rumble(int device_id, float low_frequency_intensity, float high_frequency_intensity, int length) {
        InputDevice device = InputDevice.getDevice(device_id);
        if (device == null) {
            return;
        }

        VibratorManager manager = device.getVibratorManager();
        int[] vibrators = manager.getVibratorIds();
        if (vibrators.length >= 2) {
            vibrate(manager.getVibrator(vibrators[0]), low_frequency_intensity, length);
            vibrate(manager.getVibrator(vibrators[1]), high_frequency_intensity, length);
        } else if (vibrators.length == 1) {
            float intensity = (low_frequency_intensity * 0.6f) + (high_frequency_intensity * 0.4f);
            vibrate(manager.getVibrator(vibrators[0]), intensity, length);
        }
    }

    private void vibrate(Vibrator vibrator, float intensity, int length) {
        if (intensity == 0.0f) {
            vibrator.cancel();
            return;
        }

        int value = Math.round(intensity * 255);
        if (value > 255) {
            value = 255;
        }
        if (value < 1) {
            vibrator.cancel();
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(length, value));
        }
        catch (Exception e) {
            // Fall back to the generic method, which uses DEFAULT_AMPLITUDE, but works even if
            // something went horribly wrong with the Android 8.0 APIs.
            vibrator.vibrate(length);
        }
    }
}

class SDLHapticHandler_API26 extends SDLHapticHandler {
    @Override
    public void run(int device_id, float intensity, int length) {
        SDLHaptic haptic = getHaptic(device_id);
        if (haptic != null) {
            if (intensity == 0.0f) {
                stop(device_id);
                return;
            }

            int vibeValue = Math.round(intensity * 255);

            if (vibeValue > 255) {
                vibeValue = 255;
            }
            if (vibeValue < 1) {
                stop(device_id);
                return;
            }
            try {
                haptic.vib.vibrate(VibrationEffect.createOneShot(length, vibeValue));
            }
            catch (Exception e) {
                // Fall back to the generic method, which uses DEFAULT_AMPLITUDE, but works even if
                // something went horribly wrong with the Android 8.0 APIs.
                haptic.vib.vibrate(length);
            }
        }
    }
}

class SDLHapticHandler {

    static class SDLHaptic {
        public int device_id;
        public String name;
        public Vibrator vib;
    }

    private final ArrayList<SDLHaptic> mHaptics;

    public SDLHapticHandler() {
        mHaptics = new ArrayList<SDLHaptic>();
    }

    public void run(int device_id, float intensity, int length) {
        SDLHaptic haptic = getHaptic(device_id);
        if (haptic != null) {
            haptic.vib.vibrate(length);
        }
    }

    public void rumble(int device_id, float low_frequency_intensity, float high_frequency_intensity, int length) {
        // Not supported in older APIs
    }

    public void stop(int device_id) {
        SDLHaptic haptic = getHaptic(device_id);
        if (haptic != null) {
            haptic.vib.cancel();
        }
    }

    public void pollHapticDevices() {
        if (Looper.myLooper() == null) {
            return;
        }

        final int deviceId_VIBRATOR_SERVICE = 999999;
        boolean hasVibratorService = false;

        /* Check VIBRATOR_SERVICE */
        Vibrator vib = (Vibrator) SDL.getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null) {
            hasVibratorService = vib.hasVibrator();

            if (hasVibratorService) {
                SDLHaptic haptic = getHaptic(deviceId_VIBRATOR_SERVICE);
                if (haptic == null) {
                    haptic = new SDLHaptic();
                    haptic.device_id = deviceId_VIBRATOR_SERVICE;
                    haptic.name = "VIBRATOR_SERVICE";
                    haptic.vib = vib;
                    mHaptics.add(haptic);
                    SDLControllerManager.nativeAddHaptic(haptic.device_id, haptic.name);
                }
            }
        }

        /* Check removed devices */
        ArrayList<Integer> removedDevices = null;
        for (SDLHaptic haptic : mHaptics) {
            int device_id = haptic.device_id;
            if (device_id != deviceId_VIBRATOR_SERVICE || !hasVibratorService) {
                if (removedDevices == null) {
                    removedDevices = new ArrayList<Integer>();
                }
                removedDevices.add(device_id);
            }  // else: don't remove the vibrator if it is still present
        }

        if (removedDevices != null) {
            for (int device_id : removedDevices) {
                SDLControllerManager.nativeRemoveHaptic(device_id);
                for (int i = 0; i < mHaptics.size(); i++) {
                    if (mHaptics.get(i).device_id == device_id) {
                        mHaptics.remove(i);
                        break;
                    }
                }
            }
        }
    }

    protected SDLHaptic getHaptic(int device_id) {
        for (SDLHaptic haptic : mHaptics) {
            if (haptic.device_id == device_id) {
                return haptic;
            }
        }
        return null;
    }
}

class SDLGenericMotionListener_API14 implements View.OnGenericMotionListener {
    // Generic Motion (mouse hover, joystick...) events go here
    @Override
    public boolean onGenericMotion(View v, MotionEvent event) {
        if (event.getSource() == InputDevice.SOURCE_JOYSTICK)
            return SDLControllerManager.handleJoystickMotionEvent(event);

        float x, y;
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();
        boolean consumed = false;

        for (int i = 0; i < pointerCount; i++) {
            int toolType = event.getToolType(i);

            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                switch (action) {
                    case MotionEvent.ACTION_SCROLL:
                        x = event.getAxisValue(MotionEvent.AXIS_HSCROLL, i);
                        y = event.getAxisValue(MotionEvent.AXIS_VSCROLL, i);
                        SDLActivity.onNativeMouse(0, action, x, y, false);
                        consumed = true;
                        break;

                    case MotionEvent.ACTION_HOVER_MOVE:
                        x = getEventX(event, i);
                        y = getEventY(event, i);

                        SDLActivity.onNativeMouse(0, action, x, y, checkRelativeEvent(event));
                        consumed = true;
                        break;

                    default:
                        break;
                }
            } else if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                switch (action) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                    case MotionEvent.ACTION_HOVER_MOVE:
                    case MotionEvent.ACTION_HOVER_EXIT:
                        x = event.getX(i);
                        y = event.getY(i);
                        float p = event.getPressure(i);
                        if (p > 1.0f) {
                            // may be larger than 1.0f on some devices
                            // see the documentation of getPressure(i)
                            p = 1.0f;
                        }

                        // BUTTON_STYLUS_PRIMARY is 2^5, so shift by 4, and apply SDL_PEN_INPUT_DOWN/SDL_PEN_INPUT_ERASER_TIP
                        int buttons = (event.getButtonState() >> 4) | (1 << (toolType == MotionEvent.TOOL_TYPE_STYLUS ? 0 : 30));

                        SDLActivity.onNativePen(event.getPointerId(i), buttons, action, x, y, p);
                        consumed = true;
                        break;
                }
            }
        }

        return consumed;
    }

    public boolean supportsRelativeMouse() {
        return false;
    }

    public boolean inRelativeMode() {
        return false;
    }

    public boolean setRelativeMouseEnabled(boolean enabled) {
        return false;
    }

    public void reclaimRelativeMouseModeIfNeeded() {

    }

    public boolean checkRelativeEvent(MotionEvent event) {
        return inRelativeMode();
    }

    public float getEventX(MotionEvent event, int pointerIndex) {
        return event.getX(pointerIndex);
    }

    public float getEventY(MotionEvent event, int pointerIndex) {
        return event.getY(pointerIndex);
    }

}

class SDLGenericMotionListener_API24 extends SDLGenericMotionListener_API14 {
    // Generic Motion (mouse hover, joystick...) events go here

    private boolean mRelativeModeEnabled;

    @Override
    public boolean supportsRelativeMouse() {
        return true;
    }

    @Override
    public boolean inRelativeMode() {
        return mRelativeModeEnabled;
    }

    @Override
    public boolean setRelativeMouseEnabled(boolean enabled) {
        mRelativeModeEnabled = enabled;
        return true;
    }

    @Override
    public float getEventX(MotionEvent event, int pointerIndex) {
        if (mRelativeModeEnabled && event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_MOUSE) {
            return event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, pointerIndex);
        } else {
            return event.getX(pointerIndex);
        }
    }

    @Override
    public float getEventY(MotionEvent event, int pointerIndex) {
        if (mRelativeModeEnabled && event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_MOUSE) {
            return event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, pointerIndex);
        } else {
            return event.getY(pointerIndex);
        }
    }
}

class SDLGenericMotionListener_API26 extends SDLGenericMotionListener_API24 {
    // Generic Motion (mouse hover, joystick...) events go here
    private boolean mRelativeModeEnabled;

    @Override
    public boolean supportsRelativeMouse() {
        return (!SDLActivity.isDeXMode() || Build.VERSION.SDK_INT >= 27 /* Android 8.1 (O_MR1) */);
    }

    @Override
    public boolean inRelativeMode() {
        return mRelativeModeEnabled;
    }

    @Override
    public boolean setRelativeMouseEnabled(boolean enabled) {
        if (!SDLActivity.isDeXMode() || Build.VERSION.SDK_INT >= 27 /* Android 8.1 (O_MR1) */) {
            if (enabled) {
                SDLActivity.getContentView().requestPointerCapture();
            } else {
                SDLActivity.getContentView().releasePointerCapture();
            }
            mRelativeModeEnabled = enabled;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void reclaimRelativeMouseModeIfNeeded() {
        if (mRelativeModeEnabled && !SDLActivity.isDeXMode()) {
            SDLActivity.getContentView().requestPointerCapture();
        }
    }

    @Override
    public boolean checkRelativeEvent(MotionEvent event) {
        return event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    @Override
    public float getEventX(MotionEvent event, int pointerIndex) {
        // Relative mouse in capture mode will only have relative for X/Y
        return event.getX(pointerIndex);
    }

    @Override
    public float getEventY(MotionEvent event, int pointerIndex) {
        // Relative mouse in capture mode will only have relative for X/Y
        return event.getY(pointerIndex);
    }
}