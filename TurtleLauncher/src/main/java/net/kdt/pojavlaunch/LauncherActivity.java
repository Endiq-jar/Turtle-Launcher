package net.kdt.pojavlaunch;

import static com.endiq.turtlelauncher.launch.LaunchGame.preLaunch;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.palette.graphics.Palette;
import com.kdt.mcgui.ProgressLayout;
import com.endiq.anim.AnimPlayer;
import com.endiq.anim.animations.Animations;
import com.endiq.turtlelauncher.InfoDistributor;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.context.ContextExecutor;
import com.endiq.turtlelauncher.databinding.ActivityLauncherBinding;
import com.endiq.turtlelauncher.event.single.LaunchGameEvent;
import com.endiq.turtlelauncher.event.single.MainBackgroundChangeEvent;
import com.endiq.turtlelauncher.event.single.PageOpacityChangeEvent;
import com.endiq.turtlelauncher.event.single.SwapToLoginEvent;
import com.endiq.turtlelauncher.event.sticky.MinecraftVersionValueEvent;
import com.endiq.turtlelauncher.event.value.AddFragmentEvent;
import com.endiq.turtlelauncher.event.value.DownloadProgressKeyEvent;
import com.endiq.turtlelauncher.event.value.InstallGameEvent;
import com.endiq.turtlelauncher.event.value.InstallLocalModpackEvent;
import com.endiq.turtlelauncher.event.value.LocalLoginEvent;
import com.endiq.turtlelauncher.event.value.MicrosoftLoginEvent;
import com.endiq.turtlelauncher.event.value.OtherLoginEvent;
import com.endiq.turtlelauncher.feature.accounts.AccountType;
import com.endiq.turtlelauncher.feature.accounts.AccountsManager;
import com.endiq.turtlelauncher.feature.accounts.LocalAccountUtils;
import com.endiq.turtlelauncher.feature.background.BackgroundManager;
import com.endiq.turtlelauncher.feature.background.BackgroundType;
import com.endiq.turtlelauncher.feature.download.item.ModLoaderWrapper;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.feature.mod.modpack.install.InstallExtra;
import com.endiq.turtlelauncher.feature.mod.modpack.install.InstallLocalModPack;
import com.endiq.turtlelauncher.feature.mod.modpack.install.ModPackInfo;
import com.endiq.turtlelauncher.feature.mod.modpack.install.ModPackUtils;
import com.endiq.turtlelauncher.feature.notice.CheckNewNotice;
import com.endiq.turtlelauncher.feature.notice.NoticeInfo;
import com.endiq.turtlelauncher.feature.update.UpdateUtils;
import com.endiq.turtlelauncher.feature.version.Version;
import com.endiq.turtlelauncher.feature.version.VersionsManager;
import com.endiq.turtlelauncher.feature.version.install.GameInstaller;
import com.endiq.turtlelauncher.feature.version.install.InstallTask;
import com.endiq.turtlelauncher.plugins.renderer.RendererPlugin;
import com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.task.Task;
import com.endiq.turtlelauncher.task.TaskExecutors;
import com.endiq.turtlelauncher.ui.activity.BaseActivity;
import com.endiq.turtlelauncher.ui.activity.ErrorActivity;
import com.endiq.turtlelauncher.ui.dialog.EditTextDialog;
import com.endiq.turtlelauncher.ui.dialog.TipDialog;
import com.endiq.turtlelauncher.ui.fragment.AccountFragment;
import com.endiq.turtlelauncher.ui.fragment.BaseFragment;
import com.endiq.turtlelauncher.ui.fragment.DownloadFragment;
import com.endiq.turtlelauncher.ui.fragment.DownloadModFragment;
import com.endiq.turtlelauncher.ui.fragment.settings.SettingsFragment;
import com.endiq.turtlelauncher.ui.subassembly.settingsbutton.ButtonType;
import com.endiq.turtlelauncher.ui.subassembly.settingsbutton.SettingsButtonWrapper;
import com.endiq.turtlelauncher.ui.subassembly.view.DraggableViewWrapper;
import com.endiq.turtlelauncher.utils.StoragePermissionsUtils;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.anim.ViewAnimUtils;
import com.endiq.turtlelauncher.utils.file.FileTools;
import com.endiq.turtlelauncher.utils.image.ImageUtils;
import com.endiq.turtlelauncher.utils.stringutils.ShiftDirection;
import com.endiq.turtlelauncher.utils.stringutils.StringUtils;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.Future;








public class LauncherActivity extends BaseActivity {
    private final AnimPlayer noticeAnimPlayer = new AnimPlayer();
    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (uris) -> {
                if (uris != null) {
                    Tools.launchModInstaller(this, uris.get(0));
                }
            });

    private ActivityLauncherBinding binding;
    private SettingsButtonWrapper mSettingsButtonWrapper;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private NotificationManager mNotificationManager;
    private Future<?> checkNotice;

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            if (mSettingsButtonWrapper == null) return;
            if (f instanceof MainMenuFragment) {
                mSettingsButtonWrapper.setButtonType(ButtonType.SETTINGS);
            } else {
                mSettingsButtonWrapper.setButtonType(ButtonType.HOME);
            }
        }
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if (taskCount > 0) {
            TaskExecutors.runInUIThread(() -> mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START));
        }
    };

    private ActivityResultLauncher<String> mRequestNotificationPermissionLauncher;
    private WeakReference<Runnable> mRequestNotificationPermissionRunnable;

    @Subscribe()
    public void event(PageOpacityChangeEvent event) {
        setPageOpacity(event.getProgress());
    }

    @Subscribe()
    public void event(MainBackgroundChangeEvent event) {
        refreshBackground();
        setPageOpacity(AllSettings.getPageOpacity().getValue());
    }

    @Subscribe()
    public void event(SwapToLoginEvent event) {
        Fragment currentFragment = getCurrentFragment();
        //如果当前可见的Fragment不为空，则判断当前的Fragment是否为AccountFragment，不是就跳转至AccountFragment
        if (currentFragment == null || getVisibleFragment(AccountFragment.TAG) != null) return;
        ZHTools.swapFragmentWithAnim(currentFragment, AccountFragment.class, AccountFragment.TAG, null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(LaunchGameEvent event) {
        if (binding.progressLayout.hasProcesses()) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }

        Version version = VersionsManager.INSTANCE.getCurrentVersion();
        if (version == null) {
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return;
        }

        if (AccountsManager.INSTANCE.getAllAccounts().isEmpty()) {
            Toast.makeText(this, R.string.account_no_saved_accounts, Toast.LENGTH_LONG).show();
            EventBus.getDefault().post(new SwapToLoginEvent());
            return;
        }

        RendererPlugin rendererPlugin = RendererPluginManager.getConfigurablePluginOrNull(version.getRenderer());
        if (rendererPlugin != null) {
            StoragePermissionsUtils.checkPermissions(
                    this,
                    R.string.generic_warning,
                    getString(R.string.permissions_storage_for_renderer_config, rendererPlugin.getDisplayName(), InfoDistributor.APP_NAME),
                    new StoragePermissionsUtils.PermissionGranted() {
                        @Override
                        public void granted() {
                            launchGame(version);
                        }

                        @Override
                        public void cancelled() {
                            launchGame(version);
                        }
                    }
            );
            return;
        }

        launchGame(version);
    }

    @Subscribe()
    public void event(MicrosoftLoginEvent event) {
        new MicrosoftBackgroundLogin(false, event.getUri().getQueryParameter("code")).performLogin(
                this, null,
                AccountsManager.INSTANCE.getDoneListener(),
                AccountsManager.INSTANCE.getErrorListener()
        );
    }

    @Subscribe()
    public void event(OtherLoginEvent event) {
        Task.runTask(() -> {
                    event.getAccount().save();
                    Logging.i("Account", "Saved the account : " + event.getAccount().username);
                    return null;
                }).onThrowable(e -> Logging.e("Account", "Failed to save the account : " + e))
                .finallyTask(() -> AccountsManager.INSTANCE.getDoneListener().onLoginDone(event.getAccount()))
                .execute();
    }

    @Subscribe()
    public void event(LocalLoginEvent event) {
        String userName = event.getUserName();
        MinecraftAccount localAccount = new MinecraftAccount();
        localAccount.username = userName;
        localAccount.accountType = AccountType.LOCAL.getType();
        // TurtleLauncher CRASH/BUG FIX: give local accounts a real, stable, unique
        // UUID (vanilla offline-mode scheme) instead of leaving profileId at
        // "00000000-0000-0000-0000-000000000000" for every local account — see
        // MinecraftAccount.generateOfflineUUID() for the full explanation.
        localAccount.profileId = MinecraftAccount.generateOfflineUUID(userName).toString();
        try {
            localAccount.save();
            Logging.i("Account", "Saved the account : " + localAccount.username);
        } catch (IOException e) {
            Logging.e("Account", "Failed to save the account : " + e);
        }

        AccountsManager.INSTANCE.getDoneListener().onLoginDone(localAccount);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(InstallLocalModpackEvent event) {
        InstallExtra installExtra = event.getInstallExtra();
        if (!installExtra.startInstall) return;

        if (binding.progressLayout.hasProcesses()) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }

        File dirGameModpackFile = new File(installExtra.modpackPath);
        ModPackInfo info = ModPackUtils.determineModpack(dirGameModpackFile);
        if (info.getType() == ModPackUtils.ModPackEnum.UNKNOWN) {
            InstallLocalModPack.showUnSupportDialog(this);
        }

        String modPackName = info.getName() != null ? info.getName() : FileTools.getFileNameWithoutExtension(dirGameModpackFile);

        new EditTextDialog.Builder(this)
                .setTitle(R.string.version_install_new)
                .setEditText(modPackName)
                .setAsRequired()
                .setConfirmListener((editText, checked) -> {
                    String customName = editText.getText().toString();

                    if (FileTools.isFilenameInvalid(editText)) {
                        return false;
                    }

                    if (VersionsManager.INSTANCE.isVersionExists(customName, true)) {
                        editText.setError(getString(R.string.version_install_exists));
                        return false;
                    }

                    Task.runTask(() -> {
                        ModLoaderWrapper modLoaderWrapper = InstallLocalModPack.installModPack(this, info.getType(), dirGameModpackFile, customName);
                        if (modLoaderWrapper != null) {
                            InstallTask downloadTask = modLoaderWrapper.getDownloadTask();

                            if (downloadTask != null) {
                                runOnUiThread(() -> Toast.makeText(this, getString(R.string.modpack_prepare_mod_loader_installation), Toast.LENGTH_SHORT).show());

                                Logging.i("Install Version", "Installing ModLoader: " + modLoaderWrapper.getModLoaderVersion());
                                File file = downloadTask.run(customName);
                                if (file != null) {
                                    return new kotlin.Pair<>(modLoaderWrapper, file);
                                }
                            }
                        }
                        return null;
                    }).beforeStart(TaskExecutors.getAndroidUI(), () -> ProgressLayout.setProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.generic_waiting)).ended(filePair -> {
                        if (filePair != null) {
                            try {
                                ModPackUtils.startModLoaderInstall(filePair.getFirst(), LauncherActivity.this, filePair.getSecond(), customName);
                            } catch (Throwable e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).onThrowable(TaskExecutors.getAndroidUI(), e -> Tools.showErrorRemote(this, R.string.modpack_install_download_failed, e))
                    .finallyTask(TaskExecutors.getAndroidUI(), () -> ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE))
                    .execute();

                    return true;
                }).showDialog();
    }

    @Subscribe()
    public void event(InstallGameEvent event) {
        new GameInstaller(this, event).installGame();
    }

    @Subscribe()
    public void event(DownloadProgressKeyEvent event) {
        if (event.getObserve()) {
            binding.progressLayout.observe(event.getProgressKey());
        } else {
            binding.progressLayout.unObserve(event.getProgressKey());
        }
    }

    @Subscribe()
    public synchronized void event(AddFragmentEvent event) {
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment != null) {
            try {
                AddFragmentEvent.FragmentActivityCallBack activityCallBack = event.getFragmentActivityCallback();
                if (activityCallBack != null) {
                    activityCallBack.callBack(currentFragment.requireActivity());
                }
                ZHTools.addFragment(
                        currentFragment,
                        event.getFragmentClass(),
                        event.getFragmentTag(),
                        event.getBundle()
                );
            } catch (Exception e) {
                Logging.e("LauncherActivity", "Failed attempt to jump to a new Fragment!", e);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        processFragment();
        processViews();

        // Show What's New dialog on first launch of this version
        //showWhatsNewIfNeeded();

        mRequestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestNotificationPermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        checkNotificationPermission();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));
        ProgressKeeper.addTaskCountListener(binding.progressLayout);

        new AsyncVersionList().getVersionList(versions -> EventBus.getDefault().postSticky(
                        new MinecraftVersionValueEvent(versions)),
                false
        );

        //checkNotice();

        //检查已经下载后的包，或者检查更新
        Task.runTask(() -> {
            UpdateUtils.checkDownloadedPackage(this, false, true);
            return null;
        }).execute();
    }

    private void processFragment() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment currentFragment = getCurrentFragment();
                if (currentFragment instanceof BaseFragment && !((BaseFragment) currentFragment).onBackPressed()) {
                    //Fragment那边拒绝了返回事件
                    return;
                }

                //如果栈中只剩下1个或没有Fragment，则直接退出启动器
                if (getSupportFragmentManager().getBackStackEntryCount() <= 1) {
                    finish();
                } else {
                    getSupportFragmentManager().popBackStackImmediate();
                }
            }
        });

        FragmentManager fragmentManager = getSupportFragmentManager();
        //如果栈中没有Fragment，那么就将主Fragment添加进来
        if (fragmentManager.getBackStackEntryCount() < 1) {
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addToBackStack(MainMenuFragment.TAG)
                    .add(R.id.container_fragment, MainMenuFragment.class, null, MainMenuFragment.TAG).commit();
        }
    }

    private void processViews() {
        refreshBackground();
        setPageOpacity(AllSettings.getPageOpacity().getValue());
        if (binding.settingButton != null && binding.settingButton.getVisibility() != android.view.View.GONE) {
            mSettingsButtonWrapper = new SettingsButtonWrapper(binding.settingButton);
            mSettingsButtonWrapper.setOnTypeChangeListener(type -> ViewAnimUtils.setViewAnim(binding.settingButton, Animations.Pulse));
        }
        // Home button - go back to main menu
        if (binding.homeButton != null && binding.homeButton.getVisibility() != android.view.View.GONE) {
            binding.homeButton.setOnClickListener(v -> {
                ViewAnimUtils.setViewAnim(binding.homeButton, Animations.Pulse);
                Tools.backToMainMenu(this);
            });
        }
        // Storage Manager button
        if (binding.storageButton != null && binding.storageButton.getVisibility() != android.view.View.GONE) {
            binding.storageButton.setOnClickListener(v -> {
                Fragment storageFragment = getSupportFragmentManager().findFragmentById(binding.containerFragment.getId());
                if (storageFragment != null) {
                    ViewAnimUtils.setViewAnim(binding.storageButton, Animations.Pulse);
                    Bundle bundle = new Bundle();
                    bundle.putString(com.endiq.turtlelauncher.ui.fragment.FilesFragment.BUNDLE_LIST_PATH,
                        com.endiq.turtlelauncher.utils.path.PathManager.DIR_GAME_HOME);
                    ZHTools.swapFragmentWithAnim(storageFragment,
                        com.endiq.turtlelauncher.ui.fragment.FilesFragment.class,
                        com.endiq.turtlelauncher.ui.fragment.FilesFragment.TAG, bundle);
                }
            });
        }
        if (binding.downloadButton != null && binding.downloadButton.getVisibility() != android.view.View.GONE) {
            binding.downloadButton.setOnClickListener(v -> {
                Fragment fragment = getSupportFragmentManager().findFragmentById(binding.containerFragment.getId());
                if (fragment != null && !(fragment instanceof DownloadFragment || fragment instanceof DownloadModFragment)) {
                    ViewAnimUtils.setViewAnim(binding.downloadButton, Animations.Pulse);
                    ZHTools.swapFragmentWithAnim(fragment, DownloadFragment.class, DownloadFragment.TAG, null);
                }
            });
        }
        if (binding.settingButton != null && binding.settingButton.getVisibility() != android.view.View.GONE) {
            binding.settingButton.setOnClickListener(v -> {
                ViewAnimUtils.setViewAnim(binding.settingButton, Animations.Pulse);
                Fragment fragment = getSupportFragmentManager().findFragmentById(binding.containerFragment.getId());
                if (fragment instanceof MainMenuFragment) {
                    ZHTools.swapFragmentWithAnim(fragment, SettingsFragment.class, SettingsFragment.TAG, null);
                } else {
                    // The setting button doubles as a home button now
                    Tools.backToMainMenu(this);
                }
            });
        }
        binding.appTitleText.setText(InfoDistributor.APP_NAME);
        binding.appTitleText.setOnClickListener(v -> {
            String shiftedString = StringUtils.shiftString(binding.appTitleText.getText().toString(), ShiftDirection.RIGHT, 1);
            if (new Random().nextInt(100) < 20 && shiftedString.equals(InfoDistributor.APP_NAME)) {
                ErrorActivity.showEasterEgg(this);
                return;
            }
            binding.appTitleText.setText(shiftedString);
        });

        binding.progressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
        binding.progressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        binding.progressLayout.observe(ProgressLayout.INSTALL_RESOURCE);
        binding.progressLayout.observe(ProgressLayout.LOGIN_ACCOUNT);
        binding.progressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        binding.progressLayout.observe(ProgressLayout.CHECKING_MODS);

        binding.noticeGotButton.setOnClickListener(v -> {
            setNotice(false);
            AllSettings.getNoticeDefault().put(false).save();
        });
        new DraggableViewWrapper(binding.noticeLayout, new DraggableViewWrapper.AttributesFetcher() {
            @NonNull
            @Override
            public DraggableViewWrapper.ScreenPixels getScreenPixels() {
                return new DraggableViewWrapper.ScreenPixels(0, 0,
                        currentDisplayMetrics.widthPixels - binding.noticeLayout.getWidth(),
                        currentDisplayMetrics.heightPixels - binding.noticeLayout.getHeight());
            }

            @NonNull
            @Override
            public int[] get() {
                return new int[]{(int) binding.noticeLayout.getX(), (int) binding.noticeLayout.getY()};
            }

            @Override
            public void set(int x, int y) {
                binding.noticeLayout.setX(x);
                binding.noticeLayout.setY(y);
            }
        }).init();

        //愚人节彩蛋
        if (ZHTools.checkDate(4, 1)) binding.hair.setVisibility(View.VISIBLE);
        else binding.hair.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // TurtleLauncher: the system animation-scale / reduced-motion check is cached, so it
        // has to be invalidated here - the user may have just come back from Settings where
        // they changed it, and we'd otherwise keep using the stale answer for the whole
        // process lifetime.
        com.endiq.turtlelauncher.utils.anim.TurtleTransitions.onResume();
        com.endiq.turtlelauncher.task.TaskExecutors.setGameSessionActive(false);
        com.endiq.turtlelauncher.feature.turtle.BackgroundServiceManager.onGameSessionEnd();
        setPageOpacity(AllSettings.getPageOpacity().getValue());
        VersionsManager.INSTANCE.refresh("LauncherActivity:onResume", false);
        com.endiq.turtlelauncher.feature.turtle.AssetPrefetcher.prefetch(this);
        com.endiq.turtlelauncher.feature.turtle.LauncherWarmStart.warmStart(this);
        com.endiq.turtlelauncher.feature.maintenance.AutoCleanup.runIfDue();

        //TurtleLauncher: onCreate's check only ever fires once at cold start. This activity
        //stays alive in the background for the whole length of a Minecraft session (MainActivity
        //is the one actually running the game), so onResume is what actually fires every time
        //the user lands back on the home screen not playing - right after this method's own
        //setGameSessionActive(false)/onGameSessionEnd() calls above mark that a session just
        //ended, and also on every other return to the home screen. Same quiet, non-forced,
        //cooldown-gated check as onCreate (checkCooling()'s 5-minute gate still applies), so
        //this can't spam GitHub's API just from switching apps back and forth.
        Task.runTask(() -> {
            UpdateUtils.checkDownloadedPackage(this, false, true);
            return null;
        }).execute();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == com.endiq.turtlelauncher.feature.terracotta.TerracottaVpnService.VPN_PERMISSION_REQUEST_CODE) {
            com.endiq.turtlelauncher.feature.terracotta.Terracotta.onVpnPermissionResult(this, resultCode == RESULT_OK);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding.progressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(binding.progressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
        ContextExecutor.clearActivity();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    private void launchGame(Version version) {
        LocalAccountUtils.checkUsageAllowed(new LocalAccountUtils.CheckResultListener() {
            @Override
            public void onUsageAllowed() {
                preLaunch(LauncherActivity.this, version);
            }

            @Override
            public void onUsageDenied() {
                if (!AllSettings.getLocalAccountReminders().getValue()) {
                    preLaunch(LauncherActivity.this, version);
                } else {
                    LocalAccountUtils.openDialog(LauncherActivity.this, checked -> {
                                LocalAccountUtils.saveReminders(checked);
                                preLaunch(LauncherActivity.this, version);
                            },
                            getString(R.string.account_no_microsoft_account) + getString(R.string.account_purchase_minecraft_account_tip),
                            R.string.account_continue_to_launch_the_game);
                }
            }
        });
    }

    private void checkNotice() {
        checkNotice = TaskExecutors.getDefault().submit(() -> CheckNewNotice.checkNewNotice(noticeInfo -> {
            if (checkNotice.isCancelled() || noticeInfo == null) {
                return;
            }
            //当偏好设置内是开启通知栏 或者 检测到通知编号不为偏好设置里保存的值时，显示通知栏
            if (AllSettings.getNoticeDefault().getValue() ||
                    (noticeInfo.numbering != AllSettings.getNoticeNumbering().getValue())) {
                TaskExecutors.runInUIThread(() -> setNotice(true));
                AllSettings.getNoticeDefault().put(true)
                        .put(AllSettings.getNoticeNumbering(), noticeInfo.numbering)
                        .save();
            }
        }));
    }

    private void setNotice(boolean show) {
        if (show) {
            NoticeInfo noticeInfo = CheckNewNotice.getNoticeInfo();
            if (noticeInfo != null) {
                binding.noticeGotButton.setClickable(true);

                binding.noticeTitleView.setText(noticeInfo.title);
                binding.noticeMessageView.setText(noticeInfo.content);
                binding.noticeDateView.setText(noticeInfo.date);

                Linkify.addLinks(binding.noticeMessageView, Linkify.WEB_URLS);
                binding.noticeMessageView.setMovementMethod(LinkMovementMethod.getInstance());

                noticeAnimPlayer.clearEntries();
                noticeAnimPlayer.apply(new AnimPlayer.Entry(binding.noticeLayout, Animations.BounceEnlarge))
                        .setOnStart(() -> binding.noticeLayout.setVisibility(View.VISIBLE))
                        .start();
            }
        } else {
            binding.noticeGotButton.setClickable(false);

            noticeAnimPlayer.clearEntries();
            noticeAnimPlayer.apply(new AnimPlayer.Entry(binding.noticeLayout, Animations.BounceShrink))
                    .setOnStart(() -> binding.noticeLayout.setVisibility(View.VISIBLE))
                    .setOnEnd(() -> binding.noticeLayout.setVisibility(View.GONE))
                    .start();
        }
    }

    private void refreshBackground() {
        BackgroundManager.setBackgroundImage(this, BackgroundType.MAIN_MENU, binding.backgroundView, this::refreshTopBarColor);
    }

    private void refreshTopBarColor(boolean loadFromBackground) {
        int backgroundMenuTop = ContextCompat.getColor(this, R.color.background_menu_top);

        if (loadFromBackground) {
            Bitmap bitmap = ImageUtils.getBitmapFromImageView(binding.backgroundView);
            if (bitmap != null) {
                Palette palette = Palette.from(bitmap).generate();

                boolean isDarkMode = ZHTools.isDarkMode(this);
                if (binding.topLayout != null && binding.topLayout.getVisibility() != android.view.View.GONE) {
                    binding.topLayout.setBackgroundColor(
                            isDarkMode ?
                                    palette.getDarkVibrantColor(backgroundMenuTop) :
                                    palette.getLightVibrantColor(backgroundMenuTop)
                    );
                }

                int mutedColor = isDarkMode ?
                        palette.getLightMutedColor(0xFFFFFFFF) :
                        palette.getDarkMutedColor(0xFFFFFFFF);

                ColorStateList colorStateList = ColorStateList.valueOf(mutedColor);
                binding.appTitleText.setTextColor(mutedColor);
                if (binding.downloadButton != null && binding.downloadButton.getVisibility() != android.view.View.GONE)
                    binding.downloadButton.setImageTintList(colorStateList);
                if (binding.settingButton != null && binding.settingButton.getVisibility() != android.view.View.GONE)
                    binding.settingButton.setImageTintList(colorStateList);

                return;
            }
        }
        if (binding.topLayout != null && binding.topLayout.getVisibility() != android.view.View.GONE)
            binding.topLayout.setBackgroundColor(backgroundMenuTop);
        binding.appTitleText.setTextColor(ContextCompat.getColor(this, R.color.menu_bar_text));
        ColorStateList colorStateList = ColorStateList.valueOf(0xFFFFFFFF);
        if (binding.downloadButton != null && binding.downloadButton.getVisibility() != android.view.View.GONE)
            binding.downloadButton.setImageTintList(colorStateList);
        if (binding.settingButton != null && binding.settingButton.getVisibility() != android.view.View.GONE)
            binding.settingButton.setImageTintList(colorStateList);
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag) {
        return checkFragmentAvailability(getSupportFragmentManager().findFragmentByTag(tag));
    }

    private Fragment getVisibleFragment(int id) {
        return checkFragmentAvailability(getSupportFragmentManager().findFragmentById(id));
    }

    private Fragment getCurrentFragment() {
        return getVisibleFragment(binding.containerFragment.getId());
    }

    private Fragment checkFragmentAvailability(Fragment fragment) {
        if (fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    private void checkNotificationPermission() {
        if (AllSettings.getSkipNotificationPermissionCheck().getValue() || ZHTools.checkForNotificationPermission()) {
            return;
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationPermissionReasoning();
            return;
        }
        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new TipDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(getString(R.string.notification_permission_dialog_text, InfoDistributor.APP_NAME, InfoDistributor.APP_NAME))
                .setConfirmClickListener(checked -> askForNotificationPermission(null))
                .setCancelClickListener(this::handleNoNotificationPermission)
                .showDialog();
    }

    private void handleNoNotificationPermission() {
        AllSettings.getSkipNotificationPermissionCheck().put(true).save();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (onSuccessRunnable != null) {
            mRequestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void setPageOpacity(int pageOpacity) {
        BigDecimal opacity = BigDecimal.valueOf(pageOpacity).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        float v = opacity.floatValue();

        binding.containerFragment.setAlpha(v);

        BigDecimal adjustedOpacity = BackgroundManager.hasBackgroundImage(BackgroundType.MAIN_MENU)
                ? opacity.subtract(BigDecimal.valueOf(0.1)).max(BigDecimal.ZERO)
                : BigDecimal.ONE;

        if (binding.topLayout != null && binding.topLayout.getVisibility() != android.view.View.GONE)
            binding.topLayout.setAlpha(adjustedOpacity.floatValue());
    }

    private void showWhatsNewIfNeeded() {
        final String CURRENT_VERSION = "v7.0";
        String shownVersion = AllSettings.getWhatsNewShownVersion().getValue();
        if (!CURRENT_VERSION.equals(shownVersion)) {
            AllSettings.getWhatsNewShownVersion().put(CURRENT_VERSION).save();
            new com.endiq.turtlelauncher.ui.dialog.WhatsNewDialog(this).show();
        }
    }
}
