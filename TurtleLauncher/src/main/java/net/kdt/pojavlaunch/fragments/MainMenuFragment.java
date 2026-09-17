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
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.databinding.FragmentLauncherBinding;
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
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;

public class MainMenuFragment extends FragmentWithAnim {
    public static final String TAG = "MainMenuFragment";
    private FragmentLauncherBinding binding;
    private ActivityResultLauncher<Object> modpackImportLauncher;
    // TurtleLauncher: backs the top bar's tasks button/badge - separate listener object
    // (not an onUpdateTaskCount() override) since BaseFragment's own TaskCountListener
    // implementation is Kotlin-final and this fragment only needs the badge, not the
    // isTaskRunning() gate BaseFragment already provides elsewhere.
    private final net.kdt.pojavlaunch.progresskeeper.TaskCountListener tasksBadgeListener = this::updateTasksBadge;

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // TurtleLauncher: Modpack Importer (see quick_actions_rail's modpack_import_button).
        // OpenDocumentWithExtension(null) falls back to "*/*" since neither .mrpack nor a
        // generic-ZIP modpack has a MIME type Android's database would recognize anyway -
        // ModPackUtils.determineModpack() sniffs the actual content after picking, same as
        // every other entry point into this install pipeline.
        modpackImportLauncher = registerForActivityResult(new OpenDocumentWithExtension(null), uris -> {
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
        // TurtleLauncher: Friends/LAN (Terracotta, which tunnels over EasyTier) was fully
        // implemented - native lib, VPN service, host/join UI - but had NO entry point
        // anywhere in the UI, so it was unreachable. This is it.
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
        binding.footerAppName.setText(com.endiq.turtlelauncher.InfoDistributor.LAUNCHER_NAME);
        binding.footerVersionText.setText("v" + com.endiq.turtlelauncher.BuildConfig.VERSION_NAME);
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

        // TurtleLauncher: launcher_nav_bar (home/storage/download/setting_button tab strip)
        // was removed from fragment_launcher.xml - the equivalent topBarStorageButton/
        // topBarDownloadButton/topBarSettingsButton listeners further below already cover
        // the same navigation, so no functionality is lost.

        binding.versionName.setSelected(true);
        binding.versionInfo.setSelected(true);

        // Top app bar: title/subtitle + quick-action icon row
        binding.homeTopBarTitle.setText(com.endiq.turtlelauncher.InfoDistributor.LAUNCHER_NAME);
        // TurtleLauncher: account manager moved here from the view_account card that used to
        // sit above the play panel - same destination (AccountFragment), just reachable from
        // the top bar now like the other quick actions. refreshAccountButton() keeps the icon
        // in sync with whichever account is currently selected.
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
        // TurtleLauncher: top-bar shortcut straight to the cursor manager/editor - previously
        // only reachable via Settings -> Control Settings -> Custom Mouse, several taps deep.
        binding.topBarCursorButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.CustomMouseFragment.class,
            com.endiq.turtlelauncher.ui.fragment.CustomMouseFragment.TAG, null));
        binding.topBarSettingsButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.settings.SettingsFragment.class,
            com.endiq.turtlelauncher.ui.fragment.settings.SettingsFragment.TAG, null));
        // TurtleLauncher: built-in AI Assistant. Runs entirely on-device - no API key, no
        // account, no network (see feature/ai/TurtleAssistant.kt) - so unlike the optional
        // AI crash help / skin filter in Settings -> Experimental there's nothing for the
        // player to configure before it works. The button is only hidden when the player
        // turns AllSettings.aiAssistantEnabled off.
        binding.topBarAiButton.setVisibility(
            com.endiq.turtlelauncher.setting.AllSettings.getAiAssistantEnabled().getValue()
                ? View.VISIBLE : View.GONE);
        binding.topBarAiButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this,
            com.endiq.turtlelauncher.ui.fragment.AiChatFragment.class,
            com.endiq.turtlelauncher.ui.fragment.AiChatFragment.TAG, null));
        // TurtleLauncher: replaces the old always-visible bottom ProgressLayout bar - tasks
        // (downloads, login, mod checks, etc, anything routed through ProgressKeeper) are
        // now checked on demand via this button instead of a permanent bar at the bottom.
        binding.topBarTasksButton.setOnClickListener(v -> showRunningTasksDialog());
        updateTasksBadge(ProgressKeeper.getTaskCount());

        // Today's Statistics dashboard
        refreshStatistics();
        refreshLastGameLog();

        populateFeaturePlugins();
        refreshCurrentVersion();
    }

    /**
     * TurtleLauncher: refresh the session-dependent dashboard cards every time the home
     * screen comes back to the foreground - most importantly after returning from a game
     * session, when a freshly-written game log must show up in the "Last Game Log" card
     * without waiting for the fragment to be recreated.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) return;
        refreshLastGameLog();
        // TurtleLauncher: a feature-plugin app may have been installed (or uninstalled)
        // while the launcher was in the background - LauncherActivity.onResume's plugin
        // re-scan already refreshed FeaturePluginManager's list by this point, so rebuilding
        // the Quick Actions rows here makes a freshly installed plugin app show up without
        // a restart (and removes one that got uninstalled). Cheap: removeAllViews + a
        // handful of rows.
        populateFeaturePlugins();
    }

    /** Populates the weekly playtime chart and today's total from {@link DailyPlaytimeStats}. */
    private void refreshStatistics() {
        long[] weekMs = DailyPlaytimeStats.getThisWeekMs();
        float[] weekHours = new float[weekMs.length];
        for (int i = 0; i < weekMs.length; i++) weekHours[i] = weekMs[i] / 3600000f;
        binding.statsChart.setData(weekHours);
    }

    /**
     * Populates the "Last Game Log" card and wires it to open the full log in
     * {@link LogViewerFragment}.
     *
     * <p>TurtleLauncher fix: this used to read only {@link CrashAnalyzer#getLastLogText()} -
     * in-memory state of the crash analysis, which (a) is only ever set in the process that
     * ran the analysis (the :game process), and (b) dies with that process. Back on the home
     * screen the card therefore always said "No log available yet", even though the game log
     * itself exists on disk. It now falls back to the real saved game log files
     * ({@link com.endiq.turtlelauncher.feature.log.LatestLogResolver#resolveLastGameLogFile()},
     * i.e. Minecraft's own logs/latest.log and the launcher's latestlog.txt - the latter now
     * actually contains the game's output, see GameOutputCapture), so the card keeps showing
     * the last session's log across restarts.
     */
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

    /** Last non-empty line of [text], trimmed and capped so it fits the card preview.
     *  Skips the launcher's own bookkeeping lines (e.g. "Java Exit code: 0") so the card
     *  shows an actual game-output line instead. */
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

    /**
     * Adds one Quick Actions row per discovered {@link com.endiq.turtlelauncher.plugins.feature.FeaturePlugin}
     * (see {@link com.endiq.turtlelauncher.plugins.feature.FeaturePluginManager} for the discovery
     * contract). This is the whole point of the feature-plugin architecture: a new launcher feature
     * shipped as a separate installed app shows up here automatically, without this Fragment/the
     * launcher APK needing to change at all.
     */
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

    /**
     * Updates the small count badge on the top bar's tasks button. Purely visual -
     * BaseFragment's own isTaskRunning() gate (used by e.g. runInstallerWithConfirmation
     * below) is unaffected by this.
     *
     * TurtleLauncher CRASH FIX - CalledFromWrongThreadException. This is registered as a
     * TaskCountListener on ProgressKeeper, which calls it synchronously from whatever
     * thread reported the progress change (see ProgressKeeper.waitUntilDone()'s own
     * javadoc warning about this). ModParser's mod-scan finishing on its background
     * executor and calling ProgressLayout.clearProgress() is one such caller. Every other
     * TaskCountListener in the app already hops to the UI thread before touching anything
     * (ProgressLayout.onUpdateTaskCount() posts, ProgressService.onUpdateTaskCount() uses
     * this same TaskExecutors.runInUIThread()) - this one touched the TextView directly
     * and was the odd one out. binding is re-checked inside the posted runnable since the
     * fragment's view can be destroyed between the post and it actually running.
     */
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
        com.endiq.turtlelauncher.databinding.DialogRunningTasksBinding dialogBinding =
                com.endiq.turtlelauncher.databinding.DialogRunningTasksBinding.inflate(getLayoutInflater());

        // TurtleLauncher fix: this used to render one point-in-time snapshot when the dialog
        // opened and never touch it again, so a download that kept progressing while the
        // dialog stayed open just sat frozen at whatever percentage it was at open time.
        // Now it polls ProgressKeeper every 400ms for as long as the dialog is showing and
        // patches progress/text on existing rows in place (keyed by progressKey), adds rows
        // for tasks that start after the dialog opened, and removes rows for tasks that
        // finish - so the list tracks reality instead of a snapshot.
        java.util.Map<String, com.kdt.mcgui.TextProgressBar> rowsByKey = new java.util.HashMap<>();
        android.os.Handler refreshHandler = TaskExecutors.getUIHandler();
        Runnable[] refreshRunnableHolder = new Runnable[1];

        refreshRunnableHolder[0] = () -> {
            if (!isAdded()) return; // fragment detached - stop polling, dialog will be dismissed with it

            java.util.List<ProgressKeeper.Snapshot> snapshots = ProgressKeeper.getSnapshots();
            dialogBinding.tasksDialogEmpty.setVisibility(snapshots.isEmpty() ? View.VISIBLE : View.GONE);

            java.util.Set<String> stillRunning = new java.util.HashSet<>();
            for (ProgressKeeper.Snapshot snapshot : snapshots) {
                stillRunning.add(snapshot.progressKey);
                com.kdt.mcgui.TextProgressBar row = rowsByKey.get(snapshot.progressKey);
                if (row == null) {
                    row = new com.kdt.mcgui.TextProgressBar(requireContext());
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

            java.util.Iterator<java.util.Map.Entry<String, com.kdt.mcgui.TextProgressBar>> it = rowsByKey.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, com.kdt.mcgui.TextProgressBar> entry = it.next();
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

    /**
     * Same text-resolution logic as ProgressLayout.LayoutProgressListener.onProgressUpdated -
     * kept in sync deliberately since both read the exact same ProgressState shape.
     */
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

    /**
     * TurtleLauncher: Modpack Importer. Copies whatever was picked (a content:// URI, since
     * it came from the system file picker) into DIR_CACHE as a real file - everything past
     * this point (format detection, name prompt, download, loader install) is the exact same
     * pipeline ModPackDownloadFragment already uses for modpacks downloaded in-app, just
     * triggered by an EventBus event instead of a direct call. See LauncherActivity's
     * InstallLocalModpackEvent subscriber and ModPackUtils.determineModpack for what happens
     * next - format is sniffed from content, not the picked file's name, so it doesn't matter
     * what extension (if any) the person's file manager shows it with.
     */
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

    // TurtleLauncher: the home page is built from distinct panels stacked down the screen, so
    // they now arrive one after another instead of all at once - staggering them is what makes
    // the screen feel assembled rather than just switched on. The play button still gets its
    // pop, which is a scale effect rather than an entrance, so it runs alongside the stagger.
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
