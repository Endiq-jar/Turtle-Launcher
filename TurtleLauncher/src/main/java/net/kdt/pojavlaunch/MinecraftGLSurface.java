package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.MainActivity.touchCharInput;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import com.endiq.turtlelauncher.event.single.RefreshHotbarEvent;
import com.endiq.turtlelauncher.feature.MCOptions;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.setting.AllStaticSettings;
import com.endiq.turtlelauncher.ui.activity.BaseActivity;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.DefaultDataProvider;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.customcontrols.mouse.AbstractTouchpad;
import net.kdt.pojavlaunch.customcontrols.mouse.AndroidPointerCapture;
import net.kdt.pojavlaunch.customcontrols.mouse.InGUIEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.TouchEventProcessor;
import net.kdt.pojavlaunch.utils.JREUtils;
import com.endiq.turtlelauncher.launch.SdlAndroidJniPrep;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;
import java.util.Locale;
import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;










/**
 * Class dealing with showing minecraft surface and taking inputs to dispatch them to minecraft
 */
public class MinecraftGLSurface extends View implements GrabListener {
    /* Gamepad object for gamepad inputs, instantiated on need */
    private Gamepad mGamepad = null;
    /* The RemapperView.Builder object allows you to set which buttons to remap */
    private final RemapperManager mInputManager = new RemapperManager(getContext(), new RemapperView.Builder(null)
            .remapA(true)
            .remapB(true)
            .remapX(true)
            .remapY(true)

            .remapLeftJoystick(true)
            .remapRightJoystick(true)
            .remapStart(true)
            .remapSelect(true)
            .remapLeftShoulder(true)
            .remapRightShoulder(true)
            .remapLeftTrigger(true)
            .remapRightTrigger(true)
            .remapDpad(true));

    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((BaseActivity) getContext()).heightPixels));

    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;

    private final InGameEventProcessor mIngameProcessor = new InGameEventProcessor(mSensitivityFactor);
    private final InGUIEventProcessor mInGUIProcessor = new InGUIEventProcessor();
    private TouchEventProcessor mCurrentTouchProcessor = mInGUIProcessor;
    private AndroidPointerCapture mPointerCapture;
    private boolean mLastGrabState = false;

    private OnRenderingStartedListener mOnRenderingStartedListener = null;
    private boolean mIsRenderingStarted = false;

    /* TurtleLauncher: true only while the native side actually has a live EGL binding to
     * mSurface's underlying Android Surface/SurfaceTexture. Guards refreshSize() (and the
     * destroy callbacks) against touching a surface Android has already torn down - see
     * markSurfaceDestroyed() below for why that matters. */
    private volatile boolean mSurfaceValid = false;

    public MinecraftGLSurface(Context context) {
        this(context, null);
    }

    public MinecraftGLSurface(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);
    }

    private void setUpPointerCapture(AbstractTouchpad touchpad) {
        if(mPointerCapture != null) mPointerCapture.detach();
        mPointerCapture = new AndroidPointerCapture(touchpad, this);
    }

    /**
     * TurtleLauncher: called once from a real (non-rebind) surfaceCreated/onSurfaceTextureAvailable
     * and again every time the surface is torn down and recreated without the JVM/native
     * renderer restarting (multi-window resize, split-screen entry/exit, some OEMs' aggressive
     * backgrounding). Marks the surface live so refreshSize() and friends are allowed to touch it.
     */
    private void markSurfaceValid() {
        mSurfaceValid = true;
    }

    /**
     * TurtleLauncher CRASH FIX (MC 26.3+ SDL): hand this view's real Android Surface to
     * SDL's Java glue, and tell SDL about surface lifecycle changes - the launcher-side half
     * of Amethyst-Android's SDL integration (their MinecraftGLSurface.setupSDL()/
     * surfaceChanged()/surfaceDestroyed() forwarding, see SdlAndroidJniPrep's class doc for
     * what is and isn't ported).
     *
     * Two things happen, and both are needed:
     *  - SDLSurface.setNativeSurface(): this is what SDLActivity.getNativeSurface() reports
     *    when SDL asks for a window, so it must be the Surface that is actually presented.
     *  - surfaceChanged()/surfaceDestroyed(): without these, SDL's idea of the window size
     *    and validity never updates after init.
     *
     * Size note, straight from Amethyst's own comment on the same call: forward the real
     * display metrics, NOT the resolution-scaled size this launcher renders at - "SDL doesn't
     * work like that, it'll render offscreen instead".
     *
     * No-op (SdlAndroidJniPrep.isActive stays false) for GLFW versions, so this never runs
     * for anything except the SDL launch path it exists for.
     */
    private void publishSurfaceToSdl(Surface surface) {
        if (!SdlAndroidJniPrep.isActive()) return;
        try {
            SDLActivity.setTurtleNativeSurface(surface);
            SDLSurface.setNativeSurface(surface);

            SDLSurface sdlSurface = SDLActivity.getSDLSurface();
            if (sdlSurface == null) return;

            if (surface != null) {
                int width = Tools.currentDisplayMetrics.widthPixels;
                int height = Tools.currentDisplayMetrics.heightPixels;
                sdlSurface.surfaceChanged(null, 0, width, height);
            } else {
                sdlSurface.surfaceDestroyed(null);
            }
        } catch (Throwable t) {
            Logging.e("MGLSurface", "publishSurfaceToSdl() failed", t);
        }
    }

    /**
     * TurtleLauncher: tells the native side to drop its EGL binding to this Android Surface
     * *before* Android finishes tearing it down, instead of never telling it at all -
     * JREUtils.releaseBridgeWindow() was declared as a native method but was dead code
     * everywhere in this codebase prior to this change, nothing ever called it. Without this,
     * a still-running render thread can end up calling eglSwapBuffers/eglMakeCurrent against a
     * Surface object Android has already destroyed (happens on multi-window resize,
     * split-screen entry, or just backgrounding for long enough that the window manager
     * reclaims the buffer) - exactly the class of native SIGSEGV/EGL_BAD_SURFACE crash this
     * project's crash analyzer exists to diagnose after the fact. Guarded on mSurfaceValid so
     * it only actually calls into native code once per real setup (never before the first
     * setup, never twice in a row), and wrapped in try/catch since it's a JNI call into a
     * prebuilt .so this module doesn't have source for.
     */
    private void markSurfaceDestroyed() {
        if (!mSurfaceValid) return;
        mSurfaceValid = false;
        publishSurfaceToSdl(null);
        try {
            JREUtils.releaseBridgeWindow();
        } catch (Throwable t) {
            Logging.e("MGLSurface", "releaseBridgeWindow() failed", t);
        }
    }

    /** Initialize the view and all its settings
     * @param isAlreadyRunning set to true to tell the view that the game is already running
     *                         (only updates the window without calling the start listener)
     * @param touchpad the optional cursor-emulating touchpad, used for touch event processing
     *                 when the cursor is not grabbed
     */
    public void start(boolean isAlreadyRunning, AbstractTouchpad touchpad){
        setUpPointerCapture(touchpad);
        mInGUIProcessor.setAbstractTouchpad(touchpad);
        if(AllSettings.getAlternateSurface().getValue()){
            SurfaceView surfaceView = new SurfaceView(getContext());
            mSurface = surfaceView;

            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(surfaceView.getHolder().getSurface());
                        publishSurfaceToSdl(surfaceView.getHolder().getSurface());
                        markSurfaceValid();
                        return;
                    }
                    isCalled = true;

                    realStart(surfaceView.getHolder().getSurface());
                    publishSurfaceToSdl(surfaceView.getHolder().getSurface());
                    markSurfaceValid();
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    refreshSize();
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    markSurfaceDestroyed();
                }
            });

            ((ViewGroup)getParent()).addView(surfaceView);
        } else {
            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            mSurface = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Surface tSurface = new Surface(surface);
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(tSurface);
                        publishSurfaceToSdl(tSurface);
                        markSurfaceValid();
                        return;
                    }
                    isCalled = true;

                    realStart(tSurface);
                    publishSurfaceToSdl(tSurface);
                    markSurfaceValid();
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    refreshSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    markSurfaceDestroyed();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                    if (!mIsRenderingStarted) {
                        mIsRenderingStarted = true;
                        // When the real UI is rendered, drop the launcher background image through
						// this listener to avoid translucency artifacts on some devices.
                        if (mOnRenderingStartedListener != null) mOnRenderingStartedListener.isStarted();
                    }
                }
            });

            ((ViewGroup)getParent()).addView(textureView);
        }


    }

    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;

        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            int toolType = e.getToolType(i);
            if(toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                if(mPointerCapture != null) {
                    mPointerCapture.handleAutomaticCapture();
                    return true;
                }
            }else if(toolType != MotionEvent.TOOL_TYPE_STYLUS) continue;

            // Mouse found
            if(CallbackBridge.isGrabbing()) return false;
            CallbackBridge.sendCursorPos(   e.getX(i) * AllStaticSettings.scaleFactor, e.getY(i) * AllStaticSettings.scaleFactor);
            return true; //mouse event handled successfully
        }
        if (mIngameProcessor == null || mInGUIProcessor == null) return true;
        return mCurrentTouchProcessor.processTouchEvent(e);
    }

    private void createGamepad(View contextView, InputDevice inputDevice) {
        mGamepad = new Gamepad(contextView, inputDevice, DefaultDataProvider.INSTANCE, true);
    }

    /**
     * The event for mouse/joystick movements
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int mouseCursorIndex = -1;

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepad == null) createGamepad(this, event.getDevice());

            mInputManager.handleMotionEventInput(getContext(), event, mGamepad);
            return true;
        }

        for(int i = 0; i < event.getPointerCount(); i++) {
            if(event.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;
            // Mouse found
            mouseCursorIndex = i;
            break;
        }
        if(mouseCursorIndex == -1) return false; // we cant consoom that, theres no mice!

        // Make sure we grabbed the mouse if necessary
        updateGrabState(CallbackBridge.isGrabbing());

        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                CallbackBridge.mouseX = (event.getX(mouseCursorIndex) * AllStaticSettings.scaleFactor);
                CallbackBridge.mouseY = (event.getY(mouseCursorIndex) * AllStaticSettings.scaleFactor);
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(event.getActionButton(),true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(event.getActionButton(),false);
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mCurrentTouchProcessor != null) {
            mCurrentTouchProcessor.dispatchTouchEvent(event, this);
        }
        return super.dispatchTouchEvent(event);
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Log.i("KeyEvent", event.toString());

        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;

        // TurtleLauncher (Zalith Launcher 2 physicalKeyImeCode port): a user-bound
        // hardware-keyboard key toggles the on-screen keyboard, for chat/sign/book entry
        // when typing on the physical keyboard is awkward (or the game swallows the keys).
        // Checked before the key reaches the game so the binding works even for keys the
        // game would otherwise consume. -1 = unbound = zero cost here.
        int imeKeyCode = AllSettings.getPhysicalKeyImeCode().getValue();
        if (imeKeyCode != -1 && eventKeycode == imeKeyCode
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            touchCharInput.switchKeyboardState();
            return true;
        }

        if(event.getRepeatCount() != 0) return true;
        int action = event.getAction();
        if(action == KeyEvent.ACTION_MULTIPLE) return true;
        // Ignore the cancelled up events. They occur when the user switches layouts.
        // In accordance with https://developer.android.com/reference/android/view/KeyEvent#FLAG_CANCELED
        if(action == KeyEvent.ACTION_UP &&
                (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            touchCharInput.dispatchKeyEvent(event);
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepad == null) createGamepad(this, event.getDevice());

            mInputManager.handleKeyEventInput(getContext(), event, mGamepad);
            return true;
        }

        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(eventKeycode);
        if(EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    /** Convert the mouse button, then send it
     * @return Whether the event was processed
     */
    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = -256;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
        }
        if(glfwButton == -256) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }





    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize() {
        if (!mSurfaceValid) {
            //TurtleLauncher: surface has been torn down (or never bound yet) - touching
            //mSurface's SurfaceTexture/SurfaceHolder here would either NPE or hand a stale
            //buffer size to a surface Android is about to discard anyway. The pending resize
            //isn't lost: markSurfaceValid() runs from the next onSurfaceTextureAvailable/
            //surfaceCreated, and MainActivity.onConfigurationChanged / onPostResume already
            //call refreshSize() again once things settle.
            Logging.w("MGLSurface", "Skipped refreshSize() while the surface is not valid");
            return;
        }
        int newWidth = Tools.getDisplayFriendlyRes(Tools.currentDisplayMetrics.widthPixels, AllStaticSettings.scaleFactor);
        int newHeight = Tools.getDisplayFriendlyRes(Tools.currentDisplayMetrics.heightPixels, AllStaticSettings.scaleFactor);
        if (newHeight < 1 || newWidth < 1) {
            Logging.e("MGLSurface", String.format(Locale.getDefault(), "Impossible resolution : %dx%d", newWidth, newHeight));
            return;
        }
        windowWidth = newWidth;
        windowHeight = newHeight;
        if(mSurface == null){
            Logging.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        if(AllSettings.getAlternateSurface().getValue()){
            SurfaceView view = (SurfaceView) mSurface;
            if(view.getHolder() != null){
                view.getHolder().setFixedSize(windowWidth, windowHeight);
            }
        }else{
            TextureView view = (TextureView)mSurface;
            if(view.getSurfaceTexture() != null){
                view.getSurfaceTexture().setDefaultBufferSize(windowWidth, windowHeight);
            }
        }

        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);
        notifySdlOfSurfaceSize();
        EventBus.getDefault().post(new RefreshHotbarEvent());
    }

    /**
     * TurtleLauncher CRASH FIX (MC 26.3+ SDL): keep SDL's own idea of the window size in step
     * with a resize (multi-window, split screen, rotation). This launcher's SDL integration
     * has no nativeResize() to call - the bundled libSDL3.so doesn't export one (checked: no
     * "nativeResize" string in the binary, unlike Amethyst's own SDL build) - so the size is
     * pushed through SDLSurface.surfaceChanged(), which is plain Java and ends up in
     * SDLActivity.nativeSetScreenResolution()/onNativeResize().
     *
     * Unscaled metrics on purpose, per Amethyst's comment on the same call: the resolution
     * scale this launcher renders at would make SDL "render offscreen instead".
     */
    private void notifySdlOfSurfaceSize() {
        // isActive is false for every non-SDL (GLFW) launch, so this is a no-op there.
        if (!SdlAndroidJniPrep.isActive()) return;
        try {
            SDLSurface sdlSurface = SDLActivity.getSDLSurface();
            if (sdlSurface != null) {
                sdlSurface.surfaceChanged(null, 0,
                        Tools.currentDisplayMetrics.widthPixels,
                        Tools.currentDisplayMetrics.heightPixels);
            }
        } catch (Throwable t) {
            Logging.e("MGLSurface", "notifySdlOfSurfaceSize() failed", t);
        }
    }

    private void realStart(Surface surface){
        // Initial size set
        refreshSize();

        //Load Minecraft options:
        MCOptions.INSTANCE.set("fullscreen", "false");
        MCOptions.INSTANCE.set("overrideWidth", String.valueOf(windowWidth));
        MCOptions.INSTANCE.set("overrideHeight", String.valueOf(windowHeight));
        MCOptions.INSTANCE.save();
        MCOptions.INSTANCE.getMcScale();

        JREUtils.setupBridgeWindow(surface);

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        post(()->updateGrabState(isGrabbing));
    }

    private TouchEventProcessor pickEventProcessor(boolean isGrabbing) {
        return isGrabbing ? mIngameProcessor : mInGUIProcessor;
    }

    private void updateGrabState(boolean isGrabbing) {
        if(mLastGrabState != isGrabbing) {
            mCurrentTouchProcessor.cancelPendingActions();
            mCurrentTouchProcessor = pickEventProcessor(isGrabbing);
            mLastGrabState = isGrabbing;
        }
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }

    public interface OnRenderingStartedListener {
        void isStarted();
    }

    public void setOnRenderingStartedListener(OnRenderingStartedListener listener) {
        mOnRenderingStartedListener = listener;
    }
}
