package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.setting.AllSettings;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlPopup;
import net.kdt.pojavlaunch.services.GameService;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;

    /* TurtleLauncher: decoded custom button image, cached across onDraw() calls - only
     * re-decoded when setProperties() runs with a changed customImagePath, not on every
     * frame/draw pass. Null when no custom image is set (the common case), in which case
     * onDraw() falls straight through to the pre-existing background/text rendering. */
    private Bitmap mCustomImage;
    private String mCustomImagePathLoaded;
    private final RectF mImageDrawRect = new RectF();
    private final Paint mImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    protected boolean mIsToggled = false;
    protected boolean mIsPointerOutOfBounds = false;

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(AllSettings.getButtonAllCaps().getValue());
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);

        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(128);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(60);
        }

        setText(properties.name);
        loadCustomImageIfNeeded();
    }

    /**
     * TurtleLauncher: (re)decodes the custom button image only when the path actually
     * changed since last time - setProperties() can be called far more often than the image
     * itself changes (any property edit re-runs it), so this avoids a BitmapFactory.decodeFile
     * disk hit on every keystroke in, say, the name EditText.
     */
    private void loadCustomImageIfNeeded() {
        String path = mProperties.customImagePath;
        if (path != null && path.equals(mCustomImagePathLoaded) && mCustomImage != null) return;
        if (mCustomImage != null) {
            mCustomImage.recycle();
            mCustomImage = null;
        }
        mCustomImagePathLoaded = path;
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (!file.isFile()) return;
        // Decoded at draw-time size, not full resolution - these are small UI buttons, and
        // a full-resolution user-picked photo decoded straight in would be wasteful (both
        // memory and, since this runs on the main thread, a real chance of jank).
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int targetSize = Math.max(1, Math.round(Math.max(getProperties().getWidth(), getProperties().getHeight())));
        int sampleSize = 1;
        while ((bounds.outWidth / (sampleSize * 2) >= targetSize) && (bounds.outHeight / (sampleSize * 2) >= targetSize)) {
            sampleSize *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;
        mCustomImage = BitmapFactory.decodeFile(path, opts);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // TurtleLauncher: custom button image draws first, as a background/icon layer -
        // super.onDraw() (which renders this TextView's text) still runs after it, so the
        // existing text/keycode label rendering keeps working unmodified on top. Clipped to
        // the same rounded-rect shape as the toggle overlay below so a custom image respects
        // the button's corner radius instead of covering it with hard square corners.
        if (mCustomImage != null && !mCustomImage.isRecycled()) {
            mImageDrawRect.set(0, 0, getWidth(), getHeight());
            int save = canvas.save();
            canvas.clipPath(roundedRectPath(mImageDrawRect, mComputedRadius));
            canvas.drawBitmap(mCustomImage, null, mImageDrawRect, mImagePaint);
            canvas.restoreToCount(save);
        }

        super.onDraw(canvas);
        if (mIsToggled || (!mProperties.isToggle && isActivated()))
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }

    private android.graphics.Path roundedRectPath(RectF rect, float radius) {
        android.graphics.Path path = new android.graphics.Path();
        path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
        return path;
    }


    public void loadEditValues(EditControlPopup editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        getControlLayoutParent().getLayout().mControlDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                //Send the event to be taken as a mouse action
                if(getProperties().passThruEnabled && CallbackBridge.isGrabbing()){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }

                //If out of bounds
                if(event.getX() < getControlView().getLeft() || event.getX() > getControlView().getRight() ||
                        event.getY() < getControlView().getTop()  || event.getY() > getControlView().getBottom()){
                    if(getProperties().isSwipeable && !mIsPointerOutOfBounds){
                        //Remove keys
                        if(!triggerToggle()) {
                            sendKeyPresses(false);
                        }
                    }
                    mIsPointerOutOfBounds = true;
                    getControlLayoutParent().onTouch(this, event);
                    break;
                }

                //Else if we now are in bounds
                if(mIsPointerOutOfBounds) {
                    getControlLayoutParent().onTouch(this, event);
                    //RE-press the button
                    if(getProperties().isSwipeable && !getProperties().isToggle){
                        sendKeyPresses(true);
                    }
                }
                mIsPointerOutOfBounds = false;
                break;

            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                if(!getProperties().isToggle){
                    sendKeyPresses(true);
                }
                break;

            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;

                if(!triggerToggle()) {
                    sendKeyPresses(false);
                }
                break;

            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= GLFW_KEY_UNKNOWN){
                sendKeyPress(keycode, CallbackBridge.getCurrentMods(), isDown);
                CallbackBridge.setModifiers(keycode, isDown);
            }else{
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) MainActivity.switchKeyboardState();
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown) getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) MainActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;

            // TurtleLauncher: Cancel - same mechanism MainActivity.dispatchKeyEvent already
            // uses for the hardware/predictive back path (sendKeyPress(GLFW_KEY_ESCAPE)) -
            // just exposed as a virtual control button too, fired on press-down like the
            // other momentary special buttons above (MOUSEPRI/MOUSEMID/MOUSESEC) rather than
            // on release, so it feels like a normal button tap.
            case ControlData.SPECIALBTN_CANCEL:
                if (isDown) sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
                break;

            // TurtleLauncher: Exit - cleanly ends the current game session and returns to the
            // launcher's home screen. Deliberately NOT the app-wide "Force Close" panic
            // button's ZHTools.killProcess() (Process.killProcess on the whole app process) -
            // that would also tear down GameService/notifications/any other launcher state
            // that has nothing to do with this one game session. Instead: stop the game's own
            // foreground service, then replace the task with a fresh LauncherActivity and
            // finish this Activity, the same graceful shutdown+navigate pattern used
            // elsewhere in this app rather than a hard kill.
            case ControlData.SPECIALBTN_EXIT:
                if (isDown) {
                    Context context = getContext();
                    context.stopService(new Intent(context, GameService.class));
                    Intent intent = new Intent(context, LauncherActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    context.startActivity(intent);
                    if (context instanceof Activity) {
                        ((Activity) context).finish();
                    }
                }
                break;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
