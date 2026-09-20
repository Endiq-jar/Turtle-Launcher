package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.endiq.anim.AnimPlayer;
import com.endiq.anim.animations.Animations;
import net.kdt.pojavlaunch.R;
import com.endiq.turtlelauncher.context.ContextExecutor;
import net.kdt.pojavlaunch.databinding.ActivityGameBinding;
import net.kdt.pojavlaunch.databinding.ViewControlMenuBinding;
import net.kdt.pojavlaunch.databinding.ViewGameMenuBinding;
import com.endiq.turtlelauncher.event.single.RefreshHotbarEvent;
import com.endiq.turtlelauncher.event.value.HotbarChangeEvent;
import com.endiq.turtlelauncher.feature.MCOptions;
import com.endiq.turtlelauncher.feature.ProfileLanguageSelector;
import com.endiq.turtlelauncher.feature.background.BackgroundManager;
import com.endiq.turtlelauncher.feature.background.BackgroundType;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.feature.version.Version;
import com.endiq.turtlelauncher.feature.version.VersionInfo;
import com.endiq.turtlelauncher.launch.LaunchGame;
import com.endiq.turtlelauncher.listener.SimpleTextWatcher;
import com.endiq.turtlelauncher.plugins.driver.DriverPluginManager;
import com.endiq.turtlelauncher.renderer.Renderers;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.setting.AllStaticSettings;
import com.endiq.turtlelauncher.task.Task;
import com.endiq.turtlelauncher.task.TaskExecutors;
import com.endiq.turtlelauncher.ui.activity.BaseActivity;
import com.endiq.turtlelauncher.ui.dialog.KeyboardDialog;
import com.endiq.turtlelauncher.ui.dialog.SelectControlsDialog;
import com.endiq.turtlelauncher.ui.dialog.SelectMouseDialog;
import com.endiq.turtlelauncher.ui.fragment.settings.VideoSettingsFragment;
import com.endiq.turtlelauncher.ui.subassembly.adapter.ObjectSpinnerAdapter;
import com.endiq.turtlelauncher.ui.subassembly.hotbar.HotbarType;
import com.endiq.turtlelauncher.ui.subassembly.hotbar.HotbarUtils;
import com.endiq.turtlelauncher.ui.subassembly.menu.ControlMenu;
import com.endiq.turtlelauncher.ui.subassembly.menu.MenuUtils;
import com.endiq.turtlelauncher.ui.subassembly.view.GameMenuViewWrapper;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.anim.AnimUtils;
import com.endiq.turtlelauncher.utils.file.FileTools;
import com.endiq.turtlelauncher.utils.stringutils.StringUtils;
import com.skydoves.powerspinner.OnSpinnerItemSelectedListener;
import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.keyboard.LwjglCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.services.GameService;
import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;
import java.io.File;
import java.io.IOException;


public class MainActivity extends BaseActivity implements ControlButtonMenuListener, EditorExitable, ServiceConnection {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    public static final String INTENT_VERSION = "intent_version";
    /** Amethyst-core intake: version id string, read by core-side flows. */
    public static final String INTENT_MINECRAFT_VERSION = "intent_minecraft_version";

    volatile public static boolean isInputStackCall;

    @SuppressLint("StaticFieldLeak")
    private static ActivityGameBinding binding = null;
    /** Amethyst-stack API: hotbar gesture index sync from CallbackBridge key events. */
    public void setmLastIndex(int a) {
        ActivityGameBinding b = binding;
        if (b != null) b.hotbarView.setmLastIndex(a);
    }

    public static TouchCharInput touchCharInput;
    private GameMenuViewWrapper mGameMenuWrapper;
    private GyroControl mGyroControl;
    private KeyboardDialog keyboardDialog;

    private Version minecraftVersion;

    private ViewGameMenuBinding mGameMenuBinding;
    private ViewControlMenuBinding mControlSettingsBinding;
    private MenuSettingsInitListener mMenuSettingsInitListener;
    boolean isInEditor;

    private SimpleTextWatcher mInputWatcher;
    private final AnimPlayer mInputPreviewAnim = new AnimPlayer();
    boolean isKeyboardVisible = false;

    private int mKeyboardOffsetPx = 0;
    private android.animation.ValueAnimator mKeyboardOffsetAnimator;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        minecraftVersion = getIntent().getParcelableExtra(INTENT_VERSION);
        if (minecraftVersion == null) throw new RuntimeException("The game version is not selected!");

        MCOptions.INSTANCE.setup(this, () -> minecraftVersion);
        if (AllSettings.getAutoSetGameLanguage().getValue()) {
            ProfileLanguageSelector.setGameLanguage(minecraftVersion, AllSettings.getGameLanguageOverridden().getValue());
        }

        Intent gameServiceIntent = new Intent(this, GameService.class);
        // Start the service a bit early
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout();
        CallbackBridge.addGrabListener(binding.mainTouchpad);
        CallbackBridge.addGrabListener(binding.mainGameRenderView);
        mGyroControl = new GyroControl(this);

        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isInEditor) {
                    binding.mainControlLayout.askToExit(MainActivity.this);
                    return;
                }
                if (binding.mainTouchCharInput.isEnabled()) {
                    // Chat input is open - just close it, don't touch the running game.
                    binding.mainTouchCharInput.disable();
                    return;
                }
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
            }
        });

        Window window = getWindow();
        // Enabling this on TextureView results in a broken white result
        if(AllSettings.getAlternateSurface().getValue()) window.setBackgroundDrawable(null);
        else window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // Set the sustained performance mode for available APIs
        window.setSustainedPerformanceMode(AllSettings.getSustainedPerformance().getValue());

        // Keep the screen from turning off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ControlLayout controlLayout = binding.mainControlLayout;
        mControlSettingsBinding = ViewControlMenuBinding.inflate(getLayoutInflater());
        new ControlMenu(this, this, mControlSettingsBinding, controlLayout, false);
        mControlSettingsBinding.saveAndExport.setVisibility(View.GONE);

        binding.mainControlLayout.setModifiable(false);

        // TurtleLauncher Control Switcher: tap to jump to the next control layout without
        // opening the game menu. Visibility follows AllSettings.controlSwitcherEnabled, which
        // the in-game menu's "Control Switcher" row (Control tab) toggles - see
        // MenuSettingsInitListener below. Icon is the keyboard's Tab key.
        refreshControlSwitcherButton();
        binding.controlSwitcherButton.setOnClickListener(v -> {
            String switchedTo = com.endiq.turtlelauncher.feature.turtle.ControlSwitcher
                    .cycleToNext(MainActivity.this, binding.mainControlLayout);
            // Only re-evaluate the menu button when the layout actually changed: a layout can
            // bring its own menu button, which is what hides the launcher's own.
            if (switchedTo != null) {
                mGameMenuWrapper.setVisibility(!binding.mainControlLayout.hasMenuButton());
            }
        });

        //Now, attach to the service. The game will only start when this happens, to make sure that we know the right state.
        bindService(gameServiceIntent, this, 0);

        // Initialise the input listener; it is installed when the IME covers the game view.
        mInputWatcher = s -> binding.inputPreview.setText(s.toString().trim());
        setupKeyboardInsetsListener();
    }

    private void setupKeyboardInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            onKeyboardVisibilityChanged(imeVisible, imeVisible ? imeInsets.bottom : 0);
            return insets;
        });
        // Insets are only (re-)dispatched on certain triggers; ask for one now so the
        // listener above actually runs at least once during setup.
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    private void onKeyboardVisibilityChanged(boolean imeVisible, int imeHeightPx) {
        if (imeVisible && !isKeyboardVisible) {
            binding.mainTouchCharInput.addTextChangedListener(mInputWatcher);
            setInputPreview(true);
            isKeyboardVisible = true;
        } else if (!imeVisible && isKeyboardVisible) {
            binding.mainTouchCharInput.removeTextChangedListener(mInputWatcher);
            setInputPreview(false);
            isKeyboardVisible = false;
        }
        animateKeyboardOffset(imeVisible ? imeHeightPx : 0);
    }

    private void animateKeyboardOffset(int targetPx) {
        if (mKeyboardOffsetPx == targetPx) return;
        if (mKeyboardOffsetAnimator != null) mKeyboardOffsetAnimator.cancel();

        mKeyboardOffsetAnimator = android.animation.ValueAnimator.ofInt(mKeyboardOffsetPx, targetPx);
        mKeyboardOffsetAnimator.setDuration(180);
        mKeyboardOffsetAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mKeyboardOffsetAnimator.addUpdateListener(anim -> applyKeyboardOffset((int) anim.getAnimatedValue()));
        mKeyboardOffsetAnimator.start();
    }

    private void applyKeyboardOffset(int offsetPx) {
        mKeyboardOffsetPx = offsetPx;
        if (binding == null) return;

        binding.mainControlLayout.setPadding(
                binding.mainControlLayout.getPaddingLeft(),
                binding.mainControlLayout.getPaddingTop(),
                binding.mainControlLayout.getPaddingRight(),
                offsetPx
        );

        ViewGroup.MarginLayoutParams previewParams =
                (ViewGroup.MarginLayoutParams) binding.inputPreviewLayout.getLayoutParams();
        previewParams.bottomMargin = offsetPx + (int) (8 * getResources().getDisplayMetrics().density);
        binding.inputPreviewLayout.setLayoutParams(previewParams);
    }

    protected void initLayout() {
        binding = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mGameMenuWrapper = new GameMenuViewWrapper(this, v -> onClickedMenu(), true);
        touchCharInput = binding.mainTouchCharInput;

        BackgroundManager.setBackgroundImage(this, BackgroundType.IN_GAME, binding.backgroundView, null);

        keyboardDialog = new KeyboardDialog(this).setShowSpecialButtons(false);

        binding.mainControlLayout.setMenuListener(this);

        binding.mainDrawerOptions.setScrimColor(Color.TRANSPARENT);
        binding.mainDrawerOptions.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        try {
            File latestLogFile = new File(PathManager.DIR_GAME_HOME, "latestlog.txt");
            if(!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
            // FIXME: is it safe for multi thread?
            GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            binding.mainTouchCharInput.setCharacterSender(new LwjglCharSender());

            Logging.i("RdrDebug","__P_renderer=" + minecraftVersion.getRenderer());
            Renderers.INSTANCE.setCurrentRenderer(this, minecraftVersion.getRenderer(), false);
            DriverPluginManager.setDriverByName(minecraftVersion.getDriver());

            setTitle("Minecraft " + minecraftVersion.getVersionName());

            // Minecraft 1.13+
            JMinecraftVersionList.Version mVersionInfo = Tools.getVersionInfo(minecraftVersion);
            isInputStackCall = mVersionInfo.arguments != null;
            CallbackBridge.nativeSetUseInputStackQueue(isInputStackCall);

            Tools.getDisplayMetrics(this);
            windowWidth = Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, 1f);
            windowHeight = Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, 1f);

            // Menu
            mGameMenuBinding = ViewGameMenuBinding.inflate(getLayoutInflater());
            mMenuSettingsInitListener = new MenuSettingsInitListener(mGameMenuBinding);

            binding.mainNavigationView.removeAllViews();
            binding.mainNavigationView.addView(mGameMenuBinding.getRoot());
            setupMenuDragHandle();

            binding.mainDrawerOptions.addDrawerListener(mMenuSettingsInitListener);
            binding.mainDrawerOptions.closeDrawers();

            binding.mainGameRenderView.setSurfaceReadyListener(() -> {
                try {
                    // Setup virtual mouse right before launching
                    if (AllSettings.getVirtualMouseStart().getValue()) {
                        binding.mainTouchpad.post(() -> binding.mainTouchpad.switchState());
                    }
                    LaunchGame.runGame(this, minecraftVersion, mVersionInfo);
                } catch (Throwable e) {
                    Tools.showErrorRemote(e);
                }
            });

            binding.mainGameRenderView.setOnRenderingStartedListener(() -> {
                // Clear the background image completely so no device shows translucent rendering.
                BackgroundManager.clearBackgroundImage(binding.backgroundView);
                Logging.i("Rendering Game", "The game rendering has started, " +
                        "and the background image has been cleared to prevent certain issues from occurring.");

                com.endiq.turtlelauncher.feature.inputstats.SessionStatsTracker.start();
            });

            if (AllSettings.getEnableLogOutput().getValue()) binding.mainLoggerView.setVisibilityWithAnim(true);

            String mcInfo = "";
            VersionInfo versionInfo = minecraftVersion.getVersionInfo();
            if (versionInfo != null) {
                mcInfo = versionInfo.getInfoString();
            }
            String tipString = StringUtils.insertNewline(
                    binding.gameTip.getText(),
                    StringUtils.insertSpace(getString(R.string.game_tip_version), minecraftVersion.getVersionName())
            );
            if (!mcInfo.isEmpty()) {
                tipString = StringUtils.insertNewline(tipString, StringUtils.insertSpace(getString(R.string.game_tip_mc_info), mcInfo));
            }
            binding.gameTip.setText(tipString);
            AnimUtils.setVisibilityAnim(binding.gameTip, 1000, true, 300, new AnimUtils.AnimationListener() {
                @Override public void onStart() {}
                @Override public void onEnd() {
                    AnimUtils.setVisibilityAnim(binding.gameTip, 15000, false, 300, null);
                }
            });
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private float mMenuDragLastX, mMenuDragLastY;
    private void setupMenuDragHandle() {
        View dragHandle = mGameMenuBinding.menuDragHandle;
        View panelRoot = mGameMenuBinding.getRoot();
        dragHandle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mMenuDragLastX = event.getRawX();
                    mMenuDragLastY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - mMenuDragLastX;
                    float dy = event.getRawY() - mMenuDragLastY;
                    mMenuDragLastX = event.getRawX();
                    mMenuDragLastY = event.getRawY();

                    float newX = panelRoot.getTranslationX() + dx;
                    float newY = panelRoot.getTranslationY() + dy;

                    int parentWidth = binding.mainNavigationView.getWidth();
                    int parentHeight = binding.mainNavigationView.getHeight();
                    if (parentWidth > 0 && parentHeight > 0) {
                        float minX = -(parentWidth - panelRoot.getWidth()) / 2f - panelRoot.getWidth() * 0.3f;
                        float maxX = (parentWidth - panelRoot.getWidth()) / 2f + panelRoot.getWidth() * 0.3f;
                        float minY = -(panelRoot.getTop());
                        float maxY = parentHeight - panelRoot.getBottom();
                        newX = Math.max(Math.min(newX, maxX), minX);
                        newY = Math.max(Math.min(newY, maxY), minY);
                    }

                    panelRoot.setTranslationX(newX);
                    panelRoot.setTranslationY(newY);
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private void refreshControlSwitcherButton() {
        binding.controlSwitcherButton.setVisibility(
                AllSettings.getControlSwitcherEnabled().getValue() ? View.VISIBLE : View.GONE);
    }

    private void loadControls() {
        try {
            // Load keys
            binding.mainControlLayout.loadLayout(minecraftVersion.getControl());
        } catch(IOException e) {
            try {
                Logging.w("MainActivity", "Unable to load the control file, loading the default now", e);
                binding.mainControlLayout.loadLayout((String) null);
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }
        mGameMenuWrapper.setVisibility(!binding.mainControlLayout.hasMenuButton());
        binding.mainControlLayout.toggleControlVisible();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
        loadControls();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (AllStaticSettings.enableGyro) mGyroControl.enable();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
    }

    @Override
    protected void onPause() {
        mGyroControl.disable();
        if (CallbackBridge.isGrabbing()){
            sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
        }
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    @Override
    protected void onStop() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.endiq.turtlelauncher.feature.inputstats.SessionStatsTracker.stop();
        com.endiq.turtlelauncher.feature.inputstats.InputStatsTracker.reset();
        if (mMenuSettingsInitListener != null) mMenuSettingsInitListener.closeSpinner();
        if (binding != null) {
            CallbackBridge.removeGrabListener(binding.mainTouchpad);
            CallbackBridge.removeGrabListener(binding.mainGameRenderView);
        }
        if (mKeyboardOffsetAnimator != null) mKeyboardOffsetAnimator.cancel();
        if (com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.INSTANCE.isRecording()) {
            com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.INSTANCE.stop(null);
        }
        ContextExecutor.clearActivity();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mGyroControl.updateOrientation();
        Tools.updateWindowSize(this);
        binding.mainGameRenderView.refreshSize();
        runOnUiThread(() -> binding.mainControlLayout.refreshControlButtonPositions());
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        TaskExecutors.getUIHandler().postDelayed(() -> {
            ActivityGameBinding b = binding;
            if (b != null) b.mainGameRenderView.refreshSize();
        }, 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            try {
                binding.mainControlLayout.loadLayout((String) null);
            } catch (IOException e) {
                Logging.e("LoadLayout", Tools.printToString(e));
            }
        } else if (requestCode == com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.REQUEST_CODE_AUDIO_CAPTURE) {
            com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.INSTANCE.onActivityResult(this, requestCode, resultCode, data);
        }
    }

    @Override
    public boolean shouldIgnoreNotch() {
        return AllSettings.getIgnoreNotch().getValue();
    }

    private void setInputPreview(boolean show) {
        mInputPreviewAnim.clearEntries();
        mInputPreviewAnim.apply(new AnimPlayer.Entry(binding.inputPreviewLayout, show ? Animations.FadeIn : Animations.FadeOut))
                .setOnStart(() -> binding.inputPreviewLayout.setVisibility(View.VISIBLE))
                .setOnEnd(() -> binding.inputPreviewLayout.setVisibility(show ? View.VISIBLE : View.GONE))
                .start();
    }

    public static void toggleMouse(Context ctx) {
        if (CallbackBridge.isGrabbing()) return;

        if (binding != null) {
            Toast.makeText(ctx, binding.mainTouchpad.switchState()
                            ? R.string.control_mouseon : R.string.control_mouseoff,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if(isInEditor) {
            if(event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if(event.getAction() == KeyEvent.ACTION_DOWN) binding.mainControlLayout.askToExit(this);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handleEvent;
        if(!(handleEvent = binding.mainGameRenderView.processKeyEvent(event))) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (binding.mainTouchCharInput.isEnabled()) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        binding.mainTouchCharInput.disable();
                    }
                    return true;
                }
                if(event.getAction() != KeyEvent.ACTION_UP) return true; // We eat it anyway
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
                return true;
            }
        }
        return handleEvent;
    }

    public static void switchKeyboardState() {
        if (binding != null) binding.mainTouchCharInput.switchKeyboardState();
    }

    private static void setUri(Context context, String input) {
        if(input.startsWith("file:")) {
            int truncLength = 5;
            if(input.startsWith("file://")) truncLength = 7;
            input = input.substring(truncLength);
            Logging.i("MainActivity", input);

            File inputFile = new File(input);
            FileTools.shareFile(context, inputFile);
            Logging.i("In-game Share File/Folder", "Start!");
        } else {
            ZHTools.openLink(context, input, "*/*");
        }
    }

    public static void openLink(String link) {
        try {
            ActivityGameBinding currentBinding = binding;
            if (currentBinding == null) {
                Logging.w("MainActivity", "openLink ignored: game UI is gone (link=" + link + ")");
                return;
            }
            Context ctx = currentBinding.mainTouchpad.getContext(); // no more better way to obtain a context statically
            if (!(ctx instanceof Activity)) return;
            ((Activity)ctx).runOnUiThread(() -> {
                try {
                    setUri(ctx, link);
                } catch (Throwable th) {
                    Tools.showError(ctx, th);
                }
            });
        } catch (Throwable t) {
            Logging.w("MainActivity", "openLink failed", t);
        }
    }

    public static void querySystemClipboard() {
        TaskExecutors.runInUIThread(()->{
            try {
                ClipboardManager clipboard = GLOBAL_CLIPBOARD;
                if (clipboard == null) {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                    return;
                }
                ClipData clipData = clipboard.getPrimaryClip();
                if(clipData == null || clipData.getItemCount() == 0) {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                    return;
                }
                ClipData.Item firstClipItem = clipData.getItemAt(0);
                //TODO: coerce to HTML if the clip item is styled
                CharSequence clipItemText = firstClipItem != null ? firstClipItem.getText() : null;
                if(clipItemText == null) {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                    return;
                }
                AWTInputBridge.nativeClipboardReceived(clipItemText.toString(), "plain");
            } catch (Throwable t) {
                Logging.w("MainActivity", "System clipboard query failed", t);
                AWTInputBridge.nativeClipboardReceived(null, null);
            }
        });
    }

    public static void putClipboardData(String data, String mimeType) {
        TaskExecutors.runInUIThread(()-> {
            try {
                if (data == null || mimeType == null) return;
                ClipboardManager clipboard = GLOBAL_CLIPBOARD;
                if (clipboard == null) return;
                ClipData clipData = null;
                switch(mimeType) {
                    case "text/plain":
                        clipData = ClipData.newPlainText("AWT Paste", data);
                        break;
                    case "text/html":
                        clipData = ClipData.newHtmlText("AWT Paste", data, data);
                }
                if(clipData != null) clipboard.setPrimaryClip(clipData);
            } catch (Throwable t) {
                Logging.w("MainActivity", "Failed to put clipboard data from the game", t);
            }
        });
    }

    @Override
    public void onClickedMenu() {
        DrawerLayout drawerLayout = binding.mainDrawerOptions;
        View navigationView = binding.mainNavigationView;

        boolean open = drawerLayout.isDrawerOpen(navigationView);
        if (open) drawerLayout.closeDrawer(navigationView);
        else drawerLayout.openDrawer(navigationView);

        navigationView.requestLayout();
    }

    @Override
    public void exitEditor() {
        try {
            MainActivity.binding.mainControlLayout.loadLayout((CustomControls)null);
            MainActivity.binding.mainControlLayout.setModifiable(false);
            System.gc();
            MainActivity.binding.mainControlLayout.loadLayout(minecraftVersion.getControl());
            mGameMenuWrapper.setVisibility(!binding.mainControlLayout.hasMenuButton());
        } catch (IOException e) {
            Tools.showError(this,e);
        }
        binding.mainNavigationView.removeAllViews();
        binding.mainNavigationView.addView(mGameMenuBinding.getRoot());
        isInEditor = false;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        binding.mainGameRenderView.start(GameService.isActive(), binding.mainTouchpad);
        GameService.setActive(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    /*
     * Android 14 (or some devices, at least) seems to dispatch the the captured mouse events as trackball events
     * due to a bug(?) somewhere(????)
     */
    private boolean checkCaptureDispatchConditions(MotionEvent event) {
        int eventSource = event.getSource();
        // On my device, the mouse sends events as a relative mouse device.
        // Not comparing with == here because apparently `eventSource` is a mask that can
        // sometimes indicate multiple sources, like in the case of InputDevice.SOURCE_TOUCHPAD
        // (which is *also* an InputDevice.SOURCE_MOUSE when controlling a cursor)
        return (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) != 0 ||
                (eventSource & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if(checkCaptureDispatchConditions(ev))
            return binding.mainGameRenderView.dispatchCapturedPointerEvent(ev);
        else return super.dispatchTrackballEvent(ev);
    }

    private class MenuSettingsInitListener implements View.OnClickListener, SeekBar.OnSeekBarChangeListener, CompoundButton.OnCheckedChangeListener, OnSpinnerItemSelectedListener<HotbarType>, DrawerLayout.DrawerListener {
        private final ViewGameMenuBinding binding;

        public MenuSettingsInitListener(ViewGameMenuBinding binding) {
            this.binding = binding;
            // Initialise the state.
            this.binding.hotbarWidth.setMax(currentDisplayMetrics.widthPixels / 2);
            this.binding.hotbarHeight.setMax(currentDisplayMetrics.heightPixels / 2);

            // Initialise the seekbar value.
            MenuUtils.initSeekBarValue(this.binding.resolutionScaler, AllSettings.getResolutionRatio().getValue(), this.binding.resolutionScalerValue, "%");
            binding.resolutionScalerPreview.setText(VideoSettingsFragment.getResolutionRatioPreview(getResources(), AllSettings.getResolutionRatio().getValue()));
            MenuUtils.initSeekBarValue(this.binding.timeLongPressTrigger, AllSettings.getTimeLongPressTrigger().getValue(), this.binding.timeLongPressTriggerValue, "ms");
            MenuUtils.initSeekBarValue(this.binding.mouseSpeed, AllSettings.getMouseSpeed().getValue(), this.binding.mouseSpeedValue, "%");
            MenuUtils.initSeekBarValue(this.binding.gyroSensitivity, AllSettings.getGyroSensitivity().getValue(), this.binding.gyroSensitivityValue, "%");
            MenuUtils.initSeekBarValue(this.binding.hotbarHeight, AllSettings.getHotbarHeight().getValue().getValue(), this.binding.hotbarHeightValue, "px");
            MenuUtils.initSeekBarValue(this.binding.hotbarWidth, AllSettings.getHotbarWidth().getValue().getValue(), this.binding.hotbarWidthValue, "px");

            // Initialise the switch state.
            this.binding.openMemoryInfo.setChecked(AllSettings.getGameMenuShowMemory().getValue());
            this.binding.openFpsInfo.setChecked(AllSettings.getGameMenuShowFPS().getValue());
            this.binding.showCpsHud.setChecked(AllSettings.getShowCpsHud().getValue());
            this.binding.showKeystrokesHud.setChecked(AllSettings.getShowKeystrokesHud().getValue());
            this.binding.showMousestrokesHud.setChecked(AllSettings.getShowMousestrokesHud().getValue());
            this.binding.showStopwatchHud.setChecked(AllSettings.getShowStopwatchHud().getValue());
            this.binding.showPlaytimeHud.setChecked(AllSettings.getShowPlaytimeHud().getValue());
            this.binding.showSystemResourcesHud.setChecked(AllSettings.getShowSystemResourcesHud().getValue());
            this.binding.showTimeHud.setChecked(AllSettings.getShowTimeHud().getValue());
            this.binding.showRamGraphHud.setChecked(AllSettings.getShowRamGraphHud().getValue());
            this.binding.showPingHud.setChecked(AllSettings.getShowPingHud().getValue());
            this.binding.showScreenshotButtonHud.setChecked(AllSettings.getShowScreenshotButtonHud().getValue());
            this.binding.disableGestures.setChecked(AllSettings.getDisableGestures().getValue());
            this.binding.disableDoubleTap.setChecked(AllSettings.getDisableDoubleTap().getValue());
            this.binding.controlSwitcher.setChecked(AllSettings.getControlSwitcherEnabled().getValue());
            // TurtleLauncher Emotes row state.
            this.binding.emotes.setChecked(AllSettings.getEmotesEnabled().getValue());
            refreshEmotesRows();
            this.binding.enableGyro.setChecked(AllSettings.getEnableGyro().getValue());
            this.binding.gyroInvertX.setChecked(AllSettings.getGyroInvertX().getValue());
            this.binding.gyroInvertY.setChecked(AllSettings.getGyroInvertY().getValue());

            refreshLayoutVisible(this.binding.timeLongPressTriggerLayout, !AllSettings.getDisableGestures().getValue());
            refreshLayoutVisible(this.binding.gyroLayout, AllSettings.getEnableGyro().getValue());

            this.binding.tabBtnDebug.setOnClickListener(v -> selectTab(this.binding.tabBtnDebug));
            this.binding.tabBtnRecording.setOnClickListener(v -> selectTab(this.binding.tabBtnRecording));
            this.binding.tabBtnControl.setOnClickListener(v -> selectTab(this.binding.tabBtnControl));
            this.binding.tabBtnHotbar.setOnClickListener(v -> selectTab(this.binding.tabBtnHotbar));
            selectTab(this.binding.tabBtnDebug);

            // Initialise the click listener.
            this.binding.forceClose.setOnClickListener(this);
            this.binding.logOutput.setOnClickListener(this);
            this.binding.sendCustomKey.setOnClickListener(this);
            this.binding.startRecording.setOnClickListener(this);
            this.binding.stopRecording.setOnClickListener(this);
            this.binding.openMemoryInfo.setOnCheckedChangeListener(this);
            this.binding.openMemoryInfoLayout.setOnClickListener(this);
            this.binding.openFpsInfo.setOnCheckedChangeListener(this);
            this.binding.openFpsInfoLayout.setOnClickListener(this);

            this.binding.showCpsHud.setOnCheckedChangeListener(this);
            this.binding.showCpsHudLayout.setOnClickListener(this);
            this.binding.showKeystrokesHud.setOnCheckedChangeListener(this);
            this.binding.showKeystrokesHudLayout.setOnClickListener(this);
            this.binding.showMousestrokesHud.setOnCheckedChangeListener(this);
            this.binding.showMousestrokesHudLayout.setOnClickListener(this);
            this.binding.showStopwatchHud.setOnCheckedChangeListener(this);
            this.binding.showStopwatchHudLayout.setOnClickListener(this);
            this.binding.showPlaytimeHud.setOnCheckedChangeListener(this);
            this.binding.showPlaytimeHudLayout.setOnClickListener(this);
            this.binding.showSystemResourcesHud.setOnCheckedChangeListener(this);
            this.binding.showSystemResourcesHudLayout.setOnClickListener(this);
            this.binding.showTimeHud.setOnCheckedChangeListener(this);
            this.binding.showTimeHudLayout.setOnClickListener(this);

            this.binding.showRamGraphHud.setOnCheckedChangeListener(this);
            this.binding.showRamGraphHudLayout.setOnClickListener(this);
            this.binding.showPingHud.setOnCheckedChangeListener(this);
            this.binding.showPingHudLayout.setOnClickListener(this);
            this.binding.showScreenshotButtonHud.setOnCheckedChangeListener(this);
            this.binding.showScreenshotButtonHudLayout.setOnClickListener(this);

            this.binding.resolutionScaler.setOnSeekBarChangeListener(this);
            this.binding.resolutionScalerRemove.setOnClickListener(this);
            this.binding.resolutionScalerAdd.setOnClickListener(this);

            this.binding.disableGestures.setOnCheckedChangeListener(this);
            this.binding.disableGesturesLayout.setOnClickListener(this);

            this.binding.disableDoubleTap.setOnCheckedChangeListener(this);
            this.binding.disableDoubleTapLayout.setOnClickListener(this);
            this.binding.controlSwitcher.setOnCheckedChangeListener(this);
            this.binding.controlSwitcherLayout.setOnClickListener(this);

            // TurtleLauncher Emotes: toggle + wheel trigger + website shortcut.
            this.binding.emotes.setOnCheckedChangeListener(this);
            this.binding.emotesLayout.setOnClickListener(this);
            this.binding.emoteWheelButton.setOnClickListener(this);
            this.binding.emoteSiteButton.setOnClickListener(this);

            this.binding.timeLongPressTrigger.setOnSeekBarChangeListener(this);
            this.binding.timeLongPressTriggerRemove.setOnClickListener(this);
            this.binding.timeLongPressTriggerAdd.setOnClickListener(this);

            this.binding.mouseSpeed.setOnSeekBarChangeListener(this);
            this.binding.mouseSpeedRemove.setOnClickListener(this);
            this.binding.mouseSpeedAdd.setOnClickListener(this);

            this.binding.customMouse.setOnClickListener(this);
            this.binding.replacementCustomcontrol.setOnClickListener(this);
            this.binding.editControl.setOnClickListener(this);

            this.binding.enableGyro.setOnCheckedChangeListener(this);
            this.binding.enableGyroLayout.setOnClickListener(this);

            this.binding.gyroSensitivity.setOnSeekBarChangeListener(this);
            this.binding.gyroSensitivityRemove.setOnClickListener(this);
            this.binding.gyroSensitivityAdd.setOnClickListener(this);

            this.binding.gyroInvertX.setOnCheckedChangeListener(this);
            this.binding.gyroInvertXLayout.setOnClickListener(this);

            this.binding.gyroInvertY.setOnCheckedChangeListener(this);
            this.binding.gyroInvertYLayout.setOnClickListener(this);

            ObjectSpinnerAdapter<HotbarType> hotbarTypeAdapter = new ObjectSpinnerAdapter<>(
                    this.binding.hotbarType,
                    hotbarType -> getString(hotbarType.getNameId())
            );
            hotbarTypeAdapter.setItems(HotbarType.getEntries());
            this.binding.hotbarType.setSpinnerAdapter(hotbarTypeAdapter);
            this.binding.hotbarType.setIsFocusable(true);
            this.binding.hotbarType.setOnSpinnerItemSelectedListener(this);
            this.binding.hotbarType.selectItemByIndex(HotbarUtils.getCurrentTypeIndex());

            this.binding.hotbarHeight.setOnSeekBarChangeListener(this);
            this.binding.hotbarHeightRemove.setOnClickListener(this);
            this.binding.hotbarHeightAdd.setOnClickListener(this);

            this.binding.hotbarWidth.setOnSeekBarChangeListener(this);
            this.binding.hotbarWidthRemove.setOnClickListener(this);
            this.binding.hotbarWidthAdd.setOnClickListener(this);
        }

        private void dialogSendCustomKey() {
            keyboardDialog.setOnMultiKeycodeSelectListener(selectedKeycodes -> {
                // Simulate pressing (and releasing) the keys simultaneously.
                Task.runTask(() -> {
                    selectedKeycodes.forEach(keycode -> sendKeyPress(keycode, true));
                    return null;
                }).ended(a -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignore) {
                    }
                    selectedKeycodes.forEach(keycode -> sendKeyPress(keycode, false));
                }).execute();
            }).show();
        }

        private void sendKeyPress(int keycode, boolean isDown) {
            System.out.println("Test keycode: " + keycode);
            int lwjglKeycode = EfficientAndroidLWJGLKeycode.getValueByIndex(keycode);
            System.out.println("Test lwjglKeycode: " + lwjglKeycode);
            if (keycode >= LwjglGlfwKeycode.GLFW_KEY_UNKNOWN) {
                CallbackBridge.sendKeyPress(lwjglKeycode, CallbackBridge.getCurrentMods(), isDown);
                CallbackBridge.setModifiers(lwjglKeycode, isDown);
            }
        }

        private void replacementCustomControls() {
            SelectControlsDialog dialog = new SelectControlsDialog(MainActivity.this, file -> {
                try {
                    MainActivity.binding.mainControlLayout.loadLayout(file.getAbsolutePath());
                    // Refresh: whether the menu button is hidden.
                    mGameMenuWrapper.setVisibility(!MainActivity.binding.mainControlLayout.hasMenuButton());
                } catch (IOException ignored) {}
            });
            dialog.setTitleText(R.string.replacement_customcontrol);
            dialog.show();
        }

        private void openCustomControls() {
            MainActivity.binding.mainControlLayout.setModifiable(true);
            MainActivity.binding.mainNavigationView.removeAllViews();
            MainActivity.binding.mainNavigationView.addView(mControlSettingsBinding.getRoot());
            mGameMenuWrapper.setVisibility(true);
            isInEditor = true;
        }

        @Override public void onClick(View v) {
            if (v == binding.forceClose) ZHTools.dialogForceClose(MainActivity.this);
            else if (v == binding.logOutput) MainActivity.binding.mainLoggerView.toggleViewWithAnim();
            else if (v == binding.sendCustomKey) dialogSendCustomKey();
            else if (v == binding.startRecording) {
                com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.INSTANCE.start(MainActivity.this);
                refreshRecordingButtons();
            }
            else if (v == binding.stopRecording) {
                com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.INSTANCE.stop(MainActivity.this);
                refreshRecordingButtons();
            }
            else if (v == binding.openMemoryInfoLayout) MenuUtils.toggleSwitchState(binding.openMemoryInfo);
            else if (v == binding.openFpsInfoLayout) MenuUtils.toggleSwitchState(binding.openFpsInfo);
            else if (v == binding.showCpsHudLayout) MenuUtils.toggleSwitchState(binding.showCpsHud);
            else if (v == binding.showKeystrokesHudLayout) MenuUtils.toggleSwitchState(binding.showKeystrokesHud);
            else if (v == binding.showMousestrokesHudLayout) MenuUtils.toggleSwitchState(binding.showMousestrokesHud);
            else if (v == binding.showStopwatchHudLayout) MenuUtils.toggleSwitchState(binding.showStopwatchHud);
            else if (v == binding.showPlaytimeHudLayout) MenuUtils.toggleSwitchState(binding.showPlaytimeHud);
            else if (v == binding.showSystemResourcesHudLayout) MenuUtils.toggleSwitchState(binding.showSystemResourcesHud);
            else if (v == binding.showTimeHudLayout) MenuUtils.toggleSwitchState(binding.showTimeHud);
            else if (v == binding.showRamGraphHudLayout) MenuUtils.toggleSwitchState(binding.showRamGraphHud);
            else if (v == binding.showPingHudLayout) MenuUtils.toggleSwitchState(binding.showPingHud);
            else if (v == binding.showScreenshotButtonHudLayout) MenuUtils.toggleSwitchState(binding.showScreenshotButtonHud);
            else if (v == binding.resolutionScalerRemove) MenuUtils.adjustSeekbar(binding.resolutionScaler, -1);
            else if (v == binding.resolutionScalerAdd) MenuUtils.adjustSeekbar(binding.resolutionScaler, 1);
            else if (v == binding.disableGesturesLayout) MenuUtils.toggleSwitchState(binding.disableGestures);
            else if (v == binding.disableDoubleTapLayout) MenuUtils.toggleSwitchState(binding.disableDoubleTap);
            else if (v == binding.controlSwitcherLayout) MenuUtils.toggleSwitchState(binding.controlSwitcher);
            else if (v == binding.emotesLayout) MenuUtils.toggleSwitchState(binding.emotes);
            else if (v == binding.emoteWheelButton) triggerEmoteWheel();
            else if (v == binding.emoteSiteButton) openEmoteWebsite();
            else if (v == binding.timeLongPressTriggerRemove) MenuUtils.adjustSeekbar(binding.timeLongPressTrigger, -1);
            else if (v == binding.timeLongPressTriggerAdd) MenuUtils.adjustSeekbar(binding.timeLongPressTrigger, 1);
            else if (v == binding.mouseSpeedRemove) MenuUtils.adjustSeekbar(binding.mouseSpeed, -1);
            else if (v == binding.mouseSpeedAdd) MenuUtils.adjustSeekbar(binding.mouseSpeed, 1);
            else if (v == binding.customMouse) new SelectMouseDialog(MainActivity.this, () -> MainActivity.binding.mainTouchpad.updateMouseDrawable()).show();
            else if (v == binding.replacementCustomcontrol) replacementCustomControls();
            else if (v == binding.editControl) openCustomControls();
            else if (v == binding.enableGyroLayout) MenuUtils.toggleSwitchState(binding.enableGyro);
            else if (v == binding.gyroSensitivityRemove) MenuUtils.adjustSeekbar(binding.gyroSensitivity, -1);
            else if (v == binding.gyroSensitivityAdd) MenuUtils.adjustSeekbar(binding.gyroSensitivity, 1);
            else if (v == binding.gyroInvertXLayout) MenuUtils.toggleSwitchState(binding.gyroInvertX);
            else if (v == binding.gyroInvertYLayout) MenuUtils.toggleSwitchState(binding.gyroInvertY);
            else if (v == binding.hotbarWidthRemove) MenuUtils.adjustSeekbar(binding.hotbarWidth, -1);
            else if (v == binding.hotbarWidthAdd) MenuUtils.adjustSeekbar(binding.hotbarWidth, 1);
            else if (v == binding.hotbarHeightRemove) MenuUtils.adjustSeekbar(binding.hotbarHeight, -1);
            else if (v == binding.hotbarHeightAdd) MenuUtils.adjustSeekbar(binding.hotbarHeight, 1);
        }

        @Override
        @SuppressLint("SetTextI18n")
        public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            updateSeekbarValue(s, !fromUser);
        }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {
            updateSeekbarValue(s, true);
        }

        private void updateSeekbarValue(SeekBar seekbar, boolean saveValue) {
            int progress = seekbar == null ? 0 : seekbar.getProgress();

            if (seekbar == binding.resolutionScaler) {
                if (saveValue) AllSettings.getResolutionRatio().put(progress).save();

                MenuUtils.updateSeekbarValue(progress, binding.resolutionScalerValue, "%");
                binding.resolutionScalerPreview.setText(VideoSettingsFragment.getResolutionRatioPreview(getResources(), progress));

                AllStaticSettings.scaleFactor = progress / 100f;
                MainActivity.binding.mainGameRenderView.refreshSize();
            } else if (seekbar == binding.timeLongPressTrigger) {
                if (saveValue) AllSettings.getTimeLongPressTrigger().put(progress).save();

                MenuUtils.updateSeekbarValue(progress, binding.timeLongPressTriggerValue, "ms");
                AllStaticSettings.timeLongPressTrigger = progress;
            } else if (seekbar == binding.mouseSpeed) {
                if (saveValue) AllSettings.getMouseSpeed().put(progress).save();

                MenuUtils.updateSeekbarValue(progress, binding.mouseSpeedValue, "%");
            } else if (seekbar == binding.gyroSensitivity) {
                if (saveValue) AllSettings.getGyroSensitivity().put(progress).save();

                MenuUtils.updateSeekbarValue(progress, binding.gyroSensitivityValue, "%");
                AllStaticSettings.gyroSensitivity = progress;
            } else if (seekbar == binding.hotbarWidth) {
                if (saveValue) AllSettings.getHotbarWidth().getValue().put(progress).save();

                MenuUtils.updateSeekbarValue(progress, binding.hotbarWidthValue, "px");
                EventBus.getDefault().post(new HotbarChangeEvent(progress, binding.hotbarHeight.getProgress()));
            } else if (seekbar == binding.hotbarHeight) {
                if (saveValue) AllSettings.getHotbarHeight().getValue().put(progress).save();

                MenuUtils.updateSeekbarValue(progress, binding.hotbarHeightValue, "px");
                EventBus.getDefault().post(new HotbarChangeEvent(binding.hotbarWidth.getProgress(), progress));
            }
        }

        @Override public void onCheckedChanged(CompoundButton v, boolean isChecked) {
            if (v == binding.openMemoryInfo) {
                AllSettings.getGameMenuShowMemory().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.openFpsInfo) {
                AllSettings.getGameMenuShowFPS().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showCpsHud) {
                AllSettings.getShowCpsHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showKeystrokesHud) {
                AllSettings.getShowKeystrokesHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showMousestrokesHud) {
                AllSettings.getShowMousestrokesHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showStopwatchHud) {
                AllSettings.getShowStopwatchHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showPlaytimeHud) {
                AllSettings.getShowPlaytimeHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showSystemResourcesHud) {
                AllSettings.getShowSystemResourcesHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showTimeHud) {
                AllSettings.getShowTimeHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showRamGraphHud) {
                AllSettings.getShowRamGraphHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showPingHud) {
                AllSettings.getShowPingHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.showScreenshotButtonHud) {
                AllSettings.getShowScreenshotButtonHud().put(isChecked).save();
                mGameMenuWrapper.refreshSettingsState();
            } else if (v == binding.disableGestures) {
                refreshLayoutVisible(binding.timeLongPressTriggerLayout, !isChecked);
                AllSettings.getDisableGestures().put(isChecked).save();
            } else if (v == binding.disableDoubleTap) {
                AllSettings.getDisableDoubleTap().put(isChecked).save();
                AllStaticSettings.disableDoubleTap = isChecked;
            } else if (v == binding.controlSwitcher) {
                AllSettings.getControlSwitcherEnabled().put(isChecked).save();
                // Apply immediately - the in-game button appears/disappears without a restart.
                refreshControlSwitcherButton();
            } else if (v == binding.emotes) {
                AllSettings.getEmotesEnabled().put(isChecked).save();
                // Apply immediately - the emote action rows appear/disappear without a restart.
                refreshEmotesRows();
            } else if (v == binding.enableGyro) {
                refreshLayoutVisible(binding.gyroLayout, isChecked);
                AllSettings.getEnableGyro().put(isChecked).save();
                // Refresh the gyroscope enabled state.
                AllStaticSettings.enableGyro = isChecked;
                mGyroControl.updateOrientation();
                if (isChecked) mGyroControl.enable();
                else mGyroControl.disable();
            } else if (v == binding.gyroInvertX) {
                AllSettings.getGyroInvertX().put(isChecked).save();
                AllStaticSettings.gyroInvertX = isChecked;
            } else if (v == binding.gyroInvertY) {
                AllSettings.getGyroInvertY().put(isChecked).save();
                AllStaticSettings.gyroInvertY = isChecked;
            }
        }

        /**
         * Refresh the visibility state of the view.
         */
        private void refreshLayoutVisible(View view, boolean visible) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        /** TurtleLauncher Emotes: show/hide the emote action rows per the Emote button
         *  toggle. The toggle row itself always stays visible - it IS the toggle. */
        private void refreshEmotesRows() {
            boolean on = AllSettings.getEmotesEnabled().getValue();
            binding.emoteWheelButton.setVisibility(on ? View.VISIBLE : View.GONE);
            binding.emoteSiteButton.setVisibility(on ? View.VISIBLE : View.GONE);
        }

        private void triggerEmoteWheel() {
            try {
                MainActivity.binding.mainDrawerOptions.closeDrawers();
                int key = AllSettings.getEmoteWheelKeycode().getValue();
                if (key > 0) CallbackBridge.sendKeyPress(key);
            } catch (Throwable t) {
                Logging.w("MainActivity", "Emote wheel trigger failed", t);
            }
        }

        /** TurtleLauncher Emotes: open the community emote library (the same site the
         *  Settings -> Emotes screen embeds) in the browser, for when downloading from the
         *  WebView or managing files is not what the player wants right now. */
        private void openEmoteWebsite() {
            try {
                MainActivity.binding.mainDrawerOptions.closeDrawers();
                ZHTools.openLink(MainActivity.this, com.endiq.turtlelauncher.feature.emotes.Emotes.EMOTE_SITE_URL);
            } catch (Throwable t) {
                Logging.w("MainActivity", "Could not open the emote website", t);
            }
        }

        private void selectTab(TextView selected) {
            TextView[] buttons = { binding.tabBtnDebug, binding.tabBtnRecording, binding.tabBtnControl, binding.tabBtnHotbar };
            View[] pages = { binding.tabPageDebug, binding.tabPageRecording, binding.tabPageControl, binding.tabPageHotbar };
            for (int i = 0; i < buttons.length; i++) {
                boolean isSelected = buttons[i] == selected;
                buttons[i].setTypeface(null, isSelected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                pages[i].setVisibility(isSelected ? View.VISIBLE : View.GONE);
            }
        }

        @Override public void onItemSelected(int i, @Nullable HotbarType t, int i1, HotbarType t1) {
            if (t1 == HotbarType.AUTO) {
                binding.hotbarWidthLayout.setVisibility(View.GONE);
                binding.hotbarHeightLayout.setVisibility(View.GONE);
            } else if (t1 == HotbarType.MANUALLY) {
                binding.hotbarWidthLayout.setVisibility(View.VISIBLE);
                binding.hotbarHeightLayout.setVisibility(View.VISIBLE);
                binding.hotbarWidth.setProgress(AllSettings.getHotbarWidth().getValue().getValue());
                binding.hotbarHeight.setProgress(AllSettings.getHotbarHeight().getValue().getValue());
            }

            AllSettings.getHotbarType().put(t1.getValueName()).save();
            EventBus.getDefault().post(new RefreshHotbarEvent());
        }
        @Override public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {}
        @Override public void onDrawerOpened(@NonNull View drawerView) {
            refreshRecordingButtons();
        }
        @Override public void onDrawerClosed(@NonNull View drawerView) {}
        @Override public void onDrawerStateChanged(int newState) {
            closeSpinner();
        }

        public void closeSpinner() {
            binding.hotbarType.dismiss();
        }

        public void refreshRecordingButtons() {
            boolean recording = com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.INSTANCE.isRecording();
            binding.startRecording.setVisibility(recording ? View.GONE : View.VISIBLE);
            binding.stopRecording.setVisibility(recording ? View.VISIBLE : View.GONE);
        }
    }
}
