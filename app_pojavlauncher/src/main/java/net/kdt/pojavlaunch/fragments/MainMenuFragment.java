package net.kdt.pojavlaunch.fragments;
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions;

import static com.endiq.turtlelauncher.event.single.RefreshVersionsEvent.MODE.END;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.endiq.anim.AnimPlayer;
import com.endiq.anim.animations.Animations;
import com.endiq.turtlelauncher.InfoCenter;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.databinding.FragmentLauncherBinding;
import com.endiq.turtlelauncher.event.single.AccountUpdateEvent;
import com.endiq.turtlelauncher.event.single.LaunchGameEvent;
import com.endiq.turtlelauncher.event.single.RefreshVersionsEvent;
import com.endiq.turtlelauncher.event.value.InstallLocalModpackEvent;
import com.endiq.turtlelauncher.feature.mod.modpack.install.InstallExtra;
import com.endiq.turtlelauncher.feature.log.CrashAnalyzer;
import com.endiq.turtlelauncher.feature.turtle.DailyPlaytimeStats;
import com.endiq.turtlelauncher.feature.version.Version;
import com.endiq.turtlelauncher.feature.version.utils.VersionIconUtils;
import com.endiq.turtlelauncher.feature.version.VersionInfo;
import com.endiq.turtlelauncher.feature.version.VersionsManager;
import com.endiq.turtlelauncher.task.TaskExecutors;
import com.endiq.turtlelauncher.ui.fragment.AboutFragment;
import com.endiq.turtlelauncher.ui.fragment.ControlButtonFragment;
import com.endiq.turtlelauncher.ui.fragment.FilesFragment;
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim;
import com.endiq.turtlelauncher.ui.fragment.LogViewerFragment;
import com.endiq.turtlelauncher.ui.fragment.AccountFragment;
import com.endiq.turtlelauncher.ui.fragment.VersionsListFragment;
import com.endiq.turtlelauncher.ui.subassembly.version.VersionManagerDropdown;
import com.endiq.turtlelauncher.feature.accounts.AccountsManager;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.utils.skin.SkinLoader;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.value.MinecraftAccount;
import com.endiq.turtlelauncher.utils.file.FileTools;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.anim.ViewAnimUtils;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentsWithExtension;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;

public class MainMenuFragment extends FragmentWithAnim {
    public static final String TAG = "MainMenuFragment";
    private FragmentLauncherBinding binding;
    private ActivityResultLauncher<Object> modpackImportLauncher;
    private final net.kdt.pojavlaunch.progresskeeper.TaskCountListener tasksBadgeListener = this::updateTasksBadge;

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modpackImportLauncher = registerForActivityResult(new OpenDocumentsWithExtension(null), uris -> {
            if (uris == null || uris.isEmpty()) return;
            importModpackFromUri(uris.get(0));
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherBinding.inflate(getLayoutInflater());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.aboutText.setText(InfoCenter.replaceName(requireActivity(), R.string.about_tab));
        binding.aboutButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this, AboutFragment.class, AboutFragment.TAG, null));
        binding.customControlButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this, ControlButtonFragment.class, ControlButtonFragment.TAG, null));
        binding.installJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        binding.installJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });
        binding.terracottaButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.TerracottaFragment.class,
            com.endiq.turtlelauncher.ui.fragment.TerracottaFragment.TAG, null));
        binding.shareLogsButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this, com.endiq.turtlelauncher.ui.fragment.ShareLogsFragment.class, com.endiq.turtlelauncher.ui.fragment.ShareLogsFragment.TAG, null));
        binding.modpackImportButton.setOnClickListener(v -> {
            if (ProgressKeeper.getTaskCount() == 0) {
                modpackImportLauncher.launch(null);
            } else {
                Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            }
        });
        // Footer: launcher name, version string, and GitHub link
        binding.footerAppName.setText(net.kdt.pojavlaunch.InfoDistributor.LAUNCHER_NAME);
        binding.footerVersionText.setText("v" + net.kdt.pojavlaunch.BuildConfig.VERSION_NAME);
        binding.footerGithubButton.setOnClickListener(v ->
            ZHTools.openLink(requireActivity(), com.endiq.turtlelauncher.utils.path.UrlManager.URL_HOME));

        binding.version.setOnClickListener(v -> {
            if (!isTaskRunning()) {
                ZHTools.swapFragmentWithAnim(this, VersionsListFragment.class, VersionsListFragment.TAG, null);
            } else {
                ViewAnimUtils.setViewAnim(binding.version, Animations.Shake);
                { android.content.Context toastContext = getContext(); if (toastContext != null) TaskExecutors.runInUIThread(() -> Toast.makeText(toastContext, R.string.version_manager_task_in_progress, Toast.LENGTH_SHORT).show()); }
            }
        });
        binding.managerProfileButton.setOnClickListener(v -> {
            if (!isTaskRunning()) {
                ViewAnimUtils.setViewAnim(binding.managerProfileButton, Animations.Pulse);
                new VersionManagerDropdown(this).show(binding.managerProfileButton);
            } else {
                ViewAnimUtils.setViewAnim(binding.managerProfileButton, Animations.Shake);
                { android.content.Context toastContext = getContext(); if (toastContext != null) TaskExecutors.runInUIThread(() -> Toast.makeText(toastContext, R.string.version_manager_task_in_progress, Toast.LENGTH_SHORT).show()); }
            }
        });

        binding.playButton.setOnClickListener(v -> EventBus.getDefault().post(new LaunchGameEvent()));

        // Community links
        binding.linkDiscordButton.setOnClickListener(v -> openUrl("https://discord.gg/8TfuMhM8tD"));
        binding.linkWebsiteButton.setOnClickListener(v -> openUrl("https://endiq-jar.github.io/endiq-shop/"));
        binding.linkYoutubeButton.setOnClickListener(v -> openUrl("https://youtube.com/@endiq-jar?si=9sb9OnKDJG2kUnO1"));

        binding.versionName.setSelected(true);
        binding.versionInfo.setSelected(true);

        // Top app bar: title/subtitle + quick-action icon row
        binding.homeTopBarTitle.setText(net.kdt.pojavlaunch.InfoDistributor.LAUNCHER_NAME);
        binding.topBarAccountButton.setOnClickListener(v ->
            ZHTools.swapFragmentWithAnim(this, AccountFragment.class, AccountFragment.TAG, null));
        refreshAccountButton();
        binding.topBarStorageButton.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(FilesFragment.BUNDLE_LIST_PATH, PathManager.DIR_GAME_HOME);
            ZHTools.swapFragmentWithAnim(this, FilesFragment.class, FilesFragment.TAG, bundle);
        });
        binding.topBarDownloadButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.DownloadFragment.class,
            com.endiq.turtlelauncher.ui.fragment.DownloadFragment.TAG, null));
        binding.topBarCursorButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.CustomMouseFragment.class,
            com.endiq.turtlelauncher.ui.fragment.CustomMouseFragment.TAG, null));
        binding.topBarSettingsButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.settings.SettingsFragment.class,
            com.endiq.turtlelauncher.ui.fragment.settings.SettingsFragment.TAG, null));
        binding.topBarAiButton.setVisibility(
            com.endiq.turtlelauncher.setting.AllSettings.getAiAssistantEnabled().getValue()
                ? View.VISIBLE : View.GONE);
        binding.topBarAiButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.AiChatFragment.class,
            com.endiq.turtlelauncher.ui.fragment.AiChatFragment.TAG, null));
        binding.topBarTasksButton.setOnClickListener(v -> showRunningTasksDialog());
        updateTasksBadge(ProgressKeeper.getTaskCount());

        // Today's Statistics dashboard
        refreshStatistics();
        refreshLastGameLog();

        populateFeaturePlugins();
        refreshCurrentVersion();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) return;
        refreshLastGameLog();
        populateFeaturePlugins();
    }

    /** Populates the weekly playtime chart and today's total from {@link DailyPlaytimeStats}. */
    private void refreshStatistics() {
        long[] weekMs = DailyPlaytimeStats.getThisWeekMs();
        float[] weekHours = new float[weekMs.length];
        for (int i = 0; i < weekMs.length; i++) weekHours[i] = weekMs[i] / 3600000f;
        binding.statsChart.setData(weekHours);
    }

    private void refreshLastGameLog() {
        String lastLogText = CrashAnalyzer.INSTANCE.getLastLogText();
        File logFile = com.endiq.turtlelauncher.feature.log.LatestLogResolver.resolveLastGameLogFile();

        if ((lastLogText == null || lastLogText.isEmpty()) && logFile == null) {
            binding.lastLogPreview.setText(R.string.main_last_log_none);
            binding.lastLogCard.setOnClickListener(null);
            return;
        }

        String previewSource = (lastLogText != null && !lastLogText.isEmpty())
                ? lastLogText
                : CrashAnalyzer.tailOf(logFile, 8 * 1024);
        String preview = lastNonEmptyLine(previewSource);
        if (preview.isEmpty()) {
            binding.lastLogPreview.setText(R.string.main_last_log_none);
        } else {
            binding.lastLogPreview.setText(preview);
        }

        final File targetLogFile = logFile;
        binding.lastLogCard.setOnClickListener(v -> {
            File toOpen = targetLogFile != null
                    ? targetLogFile
                    : com.endiq.turtlelauncher.feature.log.LatestLogResolver.resolveLatestLogFile();
            if (toOpen != null && toOpen.isFile()) {
                ZHTools.swapFragmentWithAnim(this, LogViewerFragment.class, LogViewerFragment.TAG,
                    LogViewerFragment.Companion.createArgs(toOpen));
            }
        });
    }

    private static String lastNonEmptyLine(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("Java Exit code")) continue;
            return trimmed.length() > 200 ? trimmed.substring(trimmed.length() - 200) : trimmed;
        }
        return "";
    }

    private void populateFeaturePlugins() {
        java.util.List<com.endiq.turtlelauncher.plugins.feature.FeaturePlugin> plugins =
                com.endiq.turtlelauncher.plugins.feature.FeaturePluginManager.getFeaturePluginList();
        binding.featurePluginsContainer.removeAllViews();
        if (plugins.isEmpty()) {
            binding.featurePluginsContainer.setVisibility(View.GONE);
            return;
        }
        binding.featurePluginsContainer.setVisibility(View.VISIBLE);
        android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
        for (com.endiq.turtlelauncher.plugins.feature.FeaturePlugin plugin : plugins) {
            View row = getLayoutInflater().inflate(R.layout.item_feature_plugin, binding.featurePluginsContainer, false);
            android.widget.ImageView icon = row.findViewById(R.id.feature_plugin_icon);
            android.widget.TextView title = row.findViewById(R.id.feature_plugin_title);
            android.widget.TextView desc = row.findViewById(R.id.feature_plugin_desc);

            title.setText(plugin.getDisplayName());
            if (plugin.getDescription().isEmpty()) {
                desc.setVisibility(View.GONE);
            } else {
                desc.setText(plugin.getDescription());
            }
            try {
                icon.setImageDrawable(packageManager.getApplicationIcon(plugin.getApplicationInfo()));
            } catch (Exception ignored) {
                icon.setImageResource(R.drawable.ic_puzzle_piece);
            }

            row.setOnClickListener(v -> {
                Intent launchIntent = packageManager.getLaunchIntentForPackage(plugin.getPackageName());
                if (launchIntent != null) {
                    try {
                        startActivity(launchIntent);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), plugin.getPackageName(), Toast.LENGTH_LONG).show();
                    }
                }
            });

            binding.featurePluginsContainer.addView(row);
        }
    }

    private void refreshCurrentVersion() {
        Version version = VersionsManager.INSTANCE.getCurrentVersion();

        int versionInfoVisibility;
        if (version != null) {
            binding.versionName.setText(version.getVersionName());
            VersionInfo versionInfo = version.getVersionInfo();
            if (versionInfo != null) {
                binding.versionInfo.setText(versionInfo.getInfoString());
                versionInfoVisibility = View.VISIBLE;
            } else versionInfoVisibility = View.GONE;

            new VersionIconUtils(version).start(binding.versionIcon);
            binding.managerProfileButton.setVisibility(View.VISIBLE);
        } else {
            binding.versionName.setText(R.string.version_no_versions);
            binding.managerProfileButton.setVisibility(View.GONE);
            versionInfoVisibility = View.GONE;
        }
        binding.versionInfo.setVisibility(versionInfoVisibility);
    }

    @Subscribe()
    public void event(RefreshVersionsEvent event) {
        if (event.getMode() == END) {
            TaskExecutors.runInUIThread(this::refreshCurrentVersion);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(AccountUpdateEvent event) {
        refreshAccountButton();
    }

    /**
     * Loads the current account's avatar into the top bar's account button, same source
     * (SkinLoader) and fallback (ic_add when no account is set yet) as the old view_account
     * card's AccountViewWrapper used, just without the name/type text an icon-only top-bar
     * button has no room for.
     */
    private void refreshAccountButton() {
        if (binding == null) return;
        MinecraftAccount account = AccountsManager.INSTANCE.getCurrentAccount();
        if (account == null) {
            binding.topBarAccountButton.setImageTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.turtle_text_secondary));
            binding.topBarAccountButton.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_add));
            return;
        }
        try {
            binding.topBarAccountButton.setImageTintList(null);
            binding.topBarAccountButton.setImageDrawable(
                SkinLoader.getAvatarDrawable(requireContext(), account, (int) Tools.dpToPx(22f))
            );
        } catch (Exception e) {
            Logging.e("MainMenuFragment", "Failed to load avatar.", e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        ProgressKeeper.addTaskCountListener(tasksBadgeListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        ProgressKeeper.removeTaskCountListener(tasksBadgeListener);
    }

    private void updateTasksBadge(int taskCount) {
        TaskExecutors.runInUIThread(() -> {
            if (binding == null) return;
            android.widget.TextView badge = binding.topBarTasksBadge;
            if (taskCount > 0) {
                badge.setText(String.valueOf(taskCount));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Shows every task ProgressKeeper currently knows about (downloads, login, mod
     * checking, JRE/runtime unpacking, etc - anything routed through ProgressKeeper.submitProgress,
     * regardless of which fixed key it uses) in a simple on-demand dialog, replacing the
     * old always-visible bottom ProgressLayout bar.
     */
    private void showRunningTasksDialog() {
        net.kdt.pojavlaunch.databinding.DialogRunningTasksBinding dialogBinding =
                net.kdt.pojavlaunch.databinding.DialogRunningTasksBinding.inflate(getLayoutInflater());

        java.util.Map<String, com.endiq.mcgui.TextProgressBar> rowsByKey = new java.util.HashMap<>();
        android.os.Handler refreshHandler = TaskExecutors.getUIHandler();
        Runnable[] refreshRunnableHolder = new Runnable[1];

        refreshRunnableHolder[0] = () -> {
            if (!isAdded()) return; // fragment detached - stop polling, dialog will be dismissed with it

            java.util.List<ProgressKeeper.Snapshot> snapshots = ProgressKeeper.getSnapshots();
            dialogBinding.tasksDialogEmpty.setVisibility(snapshots.isEmpty() ? View.VISIBLE : View.GONE);

            java.util.Set<String> stillRunning = new java.util.HashSet<>();
            for (ProgressKeeper.Snapshot snapshot : snapshots) {
                stillRunning.add(snapshot.progressKey);
                com.endiq.mcgui.TextProgressBar row = rowsByKey.get(snapshot.progressKey);
                if (row == null) {
                    row = new com.endiq.mcgui.TextProgressBar(requireContext());
                    row.setTextPadding(getResources().getDimensionPixelOffset(R.dimen._6sdp));
                    android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            getResources().getDimensionPixelOffset(R.dimen._24sdp));
                    params.bottomMargin = getResources().getDimensionPixelOffset(R.dimen._6sdp);
                    dialogBinding.tasksDialogList.addView(row, params);
                    rowsByKey.put(snapshot.progressKey, row);
                }
                row.setProgress(Math.max(snapshot.progress, 0));
                row.setText(describeSnapshot(snapshot));
            }

            java.util.Iterator<java.util.Map.Entry<String, com.endiq.mcgui.TextProgressBar>> it = rowsByKey.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, com.endiq.mcgui.TextProgressBar> entry = it.next();
                if (!stillRunning.contains(entry.getKey())) {
                    dialogBinding.tasksDialogList.removeView(entry.getValue());
                    it.remove();
                }
            }

            refreshHandler.postDelayed(refreshRunnableHolder[0], 400);
        };
        refreshRunnableHolder[0].run();

        new AlertDialog.Builder(requireContext(), R.style.CustomAlertDialogTheme)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> refreshHandler.removeCallbacks(refreshRunnableHolder[0]))
                .show();
    }

    private String describeSnapshot(ProgressKeeper.Snapshot snapshot) {
        try {
            if (snapshot.resid != -1) return getString(snapshot.resid, snapshot.varArg);
            if (snapshot.varArg.length > 0 && snapshot.varArg[0] != null) return (String) snapshot.varArg[0];
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show();
        }
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }

    private void importModpackFromUri(Uri uri) {
        // Snapshot the context up front: requireContext() from the worker thread -
        // or from the error toast posted after it - crashes if the user navigated
        // away mid-copy ("not attached to a context").
        android.content.Context appContext = requireContext().getApplicationContext();
        TaskExecutors.getDefault().execute(() -> {
            try {
                java.io.File copiedFile = FileTools.copyFileInBackground(appContext, uri, PathManager.DIR_CACHE_STRING);
                EventBus.getDefault().post(new InstallLocalModpackEvent(new InstallExtra(true, copiedFile.getAbsolutePath())));
            } catch (Exception e) {
                TaskExecutors.runInUIThread(() ->
                    Toast.makeText(appContext, R.string.modpack_install_download_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public void slideIn(AnimPlayer animPlayer) {
        com.endiq.turtlelauncher.utils.anim.TurtleTransitions.stagger(
                animPlayer,
                java.util.Arrays.asList(binding.launcherMenu, binding.playLayout),
                55L);
        animPlayer.apply(new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceEnlarge));
    }

    @Override
    public void slideOut(AnimPlayer animPlayer) {
        animPlayer.apply(new AnimPlayer.Entry(binding.launcherMenu, TurtleTransitions.exit()))
                .apply(new AnimPlayer.Entry(binding.playLayout, TurtleTransitions.exit()))
                .apply(new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceShrink));
    }
}
