package net.kdt.pojavlaunch.customcontrols.mouse;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.setting.AllStaticSettings;
import com.endiq.turtlelauncher.support.touch_controller.ContactHandler;

import org.lwjgl.glfw.CallbackBridge;

public class InGameEventProcessor implements TouchEventProcessor {
    private final Handler mGestureHandler = new Handler(Looper.getMainLooper());
    private final double mSensitivity;
    private boolean mEventTransitioned = true;
    private final PointerTracker mTracker = new PointerTracker();
    private final LeftClickGesture mLeftClickGesture = new LeftClickGesture(mGestureHandler);
    private final RightClickGesture mRightClickGesture = new RightClickGesture(mGestureHandler);

    public InGameEventProcessor(double sensitivity) {
        mSensitivity = sensitivity;
    }

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracker.startTracking(motionEvent);
                if(AllSettings.getDisableGestures().getValue()) break;
                mEventTransitioned = false;
                checkGestures();
                break;
            case MotionEvent.ACTION_MOVE:
                mTracker.trackEvent(motionEvent);
                float[] motionVector = mTracker.getMotionVector();
                // TurtleLauncher (Zalith Launcher 2 mouseCaptureSensitivity port): the
                // in-game look multiplier, adjustable 25..300% in Settings -> Mouse &
                // Keyboard. Read per event (same pattern as Touchpad reading mouseSpeed)
                // so the grab path and this touch path stay in sync with the setting.
                float captureSensitivity = AllSettings.getMouseCaptureSensitivity().getValue() / 100f;
                float deltaX = (float) (motionVector[0] * mSensitivity * captureSensitivity);
                float deltaY = (float) (motionVector[1] * mSensitivity * captureSensitivity);
                mLeftClickGesture.setMotion(deltaX, deltaY);
                mRightClickGesture.setMotion(deltaX, deltaY);
                CallbackBridge.mouseX += deltaX;
                CallbackBridge.mouseY += deltaY;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                if(AllSettings.getDisableGestures().getValue()) break;
                checkGestures();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracker.cancelTracking();
                cancelGestures(false);
        }
        return true;
    }

    @Override
    public void cancelPendingActions() {
        cancelGestures(true);
    }

    @Override
    public void dispatchTouchEvent(MotionEvent event, View view) {
        if (AllStaticSettings.useControllerProxy) {
            // Handle touch events separately to support the TouchController mod.
            ContactHandler.INSTANCE.progressEvent(event, view);
        }
    }

    private void checkGestures() {
        mLeftClickGesture.inputEvent();
        // Only register right click events if it's a fresh event stream, not one after a transition.
        // This is done to avoid problems when people hold the button for just a bit too long after
        // exiting a menu for example.
        if(!mEventTransitioned) mRightClickGesture.inputEvent();
    }

    private void cancelGestures(boolean isSwitching) {
        mEventTransitioned = true;
        mLeftClickGesture.cancel(isSwitching);
        mRightClickGesture.cancel(isSwitching);
    }
}
