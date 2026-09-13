package com.endiq.turtlelauncher.ui.activity;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.endiq.turtlelauncher.context.AccessibilityHelper;
import com.endiq.turtlelauncher.context.ContextExecutor;
import com.endiq.turtlelauncher.context.LocaleHelper;
import com.endiq.turtlelauncher.event.single.LauncherIgnoreNotchEvent;
import com.endiq.turtlelauncher.feature.accounts.AccountsManager;
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathManager;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.plugins.PluginLoader;
import com.endiq.turtlelauncher.renderer.Renderers;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.task.TaskExecutors;
import com.endiq.turtlelauncher.utils.StoragePermissionsUtils;

import net.kdt.pojavlaunch.MissingStorageActivity;
import net.kdt.pojavlaunch.Tools;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public abstract class BaseActivity extends AppCompatActivity {

    // TurtleLauncher: Environment.isExternalStorageManager() and AccountsManager.reload()'s
    // account-folder scan are both real (binder IPC / disk) I/O, and onResume() used to run
    // both of them, synchronously, on the main thread, on *every single* resume of *every*
    // activity in the app - including switching back into the running game after alt-tabbing,
    // which is exactly the moment you don't want a hitch. Neither result is ever needed
    // synchronously by the code right after onResume() returns (the storage check only feeds
    // the cached checkPermissions() getter read elsewhere, and account reload only mutates
    // AccountsManager's own thread-safe CopyOnWriteArrayList), so both now run on the shared
    // background pool instead. See TaskExecutors for the pool itself (adaptive size + lower
    // priority while a game session is active).
    private static final long PERMISSION_RECHECK_DEBOUNCE_MS = 1500L;
    private long lastPermissionCheckElapsedMs = -PERMISSION_RECHECK_DEBOUNCE_MS;

    @Override
    protected void attachBaseContext(Context newBase) {
        // TurtleLauncher: Accessibility (roadmap item 22) - UI text scale. Must happen here,
        // not onCreate(), since fontScale only takes effect via a Configuration attached before
        // the Activity's Resources are created. LocaleHelper.setLocale() runs first because it's
        // also what makes sure Settings.refreshSettings() has actually loaded AllSettings.fontScale
        // from disk before AccessibilityHelper reads it.
        super.attachBaseContext(AccessibilityHelper.wrapContext(LocaleHelper.Companion.setLocale(newBase)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocaleHelper.Companion.setLocale(this);
        // TurtleLauncher: Accessibility (roadmap item 22) - high-contrast mode + font family.
        // Both are theme overlays, so unlike fontScale they need to apply after super.onCreate()
        // has set this Activity's theme up, but before setContentView()/binding.inflate() in
        // each subclass actually inflates views against it.
        AccessibilityHelper.applyHighContrastOverlay(this);
        AccessibilityHelper.applyFontFamilyOverride(this);
        Tools.setFullscreen(this);
        Tools.updateWindowSize(this);

        checkStoragePermissions(true);
        //加载渲染器
        Renderers.INSTANCE.init(false);
        //加载插件
        PluginLoader.loadAllPlugins(this, false);
        //刷新游戏路径
        ProfilePathManager.INSTANCE.refreshPath();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        if (!Tools.checkStorageRoot()) {
            startActivity(new Intent(this, MissingStorageActivity.class));
            finish();
            return;
        }

        checkStoragePermissions(false);

        //TurtleLauncher: off the main thread - see the field comment above for why.
        TaskExecutors.getDefault().execute(AccountsManager.INSTANCE::reload);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        Tools.setFullscreen(this);
        Tools.ignoreNotch(shouldIgnoreNotch(),this);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Tools.getDisplayMetrics(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    /**
     * TurtleLauncher: on API 30+ immersive mode is driven by WindowInsetsControllerCompat
     * (see Tools#setFullscreen), which - unlike the old SYSTEM_UI_FLAG listener - does not
     * fire again by itself once the user swipes the transient system bars back into view or
     * a dialog/notification-shade/IME steals focus. Re-applying on refocus is the standard
     * fix and also happens to be a correct no-op on the legacy (<R) path.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Tools.setFullscreen(this);
    }

    /**
     * TurtleLauncher: entering/exiting multi-window (split-screen, freeform, or the
     * desktop-mode windowing some Android 14/15 tablets and ChromeOS devices default to)
     * needs the same immersive-state recompute setFullscreen() already does internally
     * (it checks Activity#isInMultiWindowMode()) - this just makes sure it actually runs
     * again on the transition instead of only on the next onResume/onPostResume.
     */
    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, @NonNull Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        Tools.setFullscreen(this);
    }

    /**
     * TurtleLauncher: Glide already registers itself as a ComponentCallbacks2 and trims its
     * own caches on this callback, so nothing to do for image memory here - this override
     * exists purely so memory pressure shows up in the log (and therefore in exported crash
     * diagnostics) instead of being invisible right up until an OOM kill.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Logging.w("BaseActivity", getClass().getSimpleName() + " received onTrimMemory(" + level + ")");
        }
    }

    @Subscribe
    public void event(LauncherIgnoreNotchEvent event) {
        Tools.ignoreNotch(shouldIgnoreNotch(),this);
    }

    /** @return Whether or not the notch should be ignored */
    public boolean shouldIgnoreNotch() {
        return AllSettings.getIgnoreNotchLauncher().getValue();
    }

    /**
     * 检查所有文件管理权限
     * @param force 是否跳过节流、同步执行检查。onCreate() 首次检查后，
     *              ProfilePathManager/ProfilePathAdapter 会立刻同步读取
     *              StoragePermissionsUtils.checkPermissions() 的缓存结果，
     *              所以这一次必须在返回前完成，不能丢到后台异步执行，
     *              否则会读到未刷新的默认值。onCreate() 每个Activity实例只跑一次，
     *              不是热路径，同步执行的开销可以接受；onResume() 才是被高频调用、
     *              真正需要异步 + 节流的地方。
     */
    private void checkStoragePermissions(boolean force) {
        if (force) {
            lastPermissionCheckElapsedMs = SystemClock.elapsedRealtime();
            StoragePermissionsUtils.checkPermissions(this);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        //TurtleLauncher: Environment.isExternalStorageManager() is a binder call into
        //system_server - cheap once, but wasteful when repeated on every onResume() of
        //every activity during fast back-and-forth navigation. The cached checkPermissions()
        //getter is what callers actually read, so a short debounce here doesn't lose any
        //real freshness (permission state only ever changes via the Settings screen, whose
        //return already triggers a fresh onResume well past the debounce window).
        if (now - lastPermissionCheckElapsedMs < PERMISSION_RECHECK_DEBOUNCE_MS) return;
        lastPermissionCheckElapsedMs = now;

        TaskExecutors.getDefault().execute(() -> StoragePermissionsUtils.checkPermissions(this));
    }
}
