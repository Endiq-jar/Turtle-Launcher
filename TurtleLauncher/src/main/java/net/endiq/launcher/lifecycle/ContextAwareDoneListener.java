package net.endiq.launcher.lifecycle;

import static net.endiq.launcher.MainActivity.INTENT_VERSION;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.endiq.mcgui.ProgressLayout;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.context.ContextExecutor;
import com.endiq.turtlelauncher.feature.mod.parser.ModChecker;
import com.endiq.turtlelauncher.feature.mod.parser.ModInfo;
import com.endiq.turtlelauncher.feature.mod.parser.ModParser;
import com.endiq.turtlelauncher.feature.mod.parser.ModParserListener;
import com.endiq.turtlelauncher.feature.version.Version;
import com.endiq.turtlelauncher.setting.AllSettings;

import net.endiq.launcher.MainActivity;
import net.endiq.launcher.Tools;
import net.endiq.launcher.progresskeeper.ProgressKeeper;
import net.endiq.launcher.tasks.AsyncMinecraftDownloader;
import net.endiq.launcher.utils.NotificationUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ContextAwareDoneListener implements AsyncMinecraftDownloader.DoneListener, ContextExecutorTask {
    private final String mErrorString;
    private final Version mVersion;

    public ContextAwareDoneListener(Context baseContext, Version version) {
        this.mErrorString = baseContext.getString(R.string.mc_download_failed);
        this.mVersion = version;
    }

    private Intent createGameStartIntent(Context context) {
        Intent mainIntent = new Intent(context, MainActivity.class);
        mainIntent.putExtra(INTENT_VERSION, mVersion);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return mainIntent;
    }

    private void executeTask() {
        ProgressKeeper.waitUntilDone(() -> ContextExecutor.executeTask(this));
    }

    @Override
    public void onDownloadDone() {
        AtomicInteger progressCount = new AtomicInteger(0);
        ModParser.checkAllMods(mVersion, new ModParserListener() {
            @Override
            public void onProgress(@NonNull ModInfo recentlyParsedModInfo, int totalFileCount) {
                int i = progressCount.incrementAndGet();
                int percent = totalFileCount > 0 ? i * 100 / totalFileCount : 100;
                ProgressLayout.setProgress(ProgressLayout.CHECKING_MODS, percent,
                        R.string.mod_check_progress_message, i, totalFileCount);
            }

            @Override
            public void onParseEnded(@NonNull List<? extends ModInfo> modInfoList) {
                ProgressLayout.clearProgress(ProgressLayout.CHECKING_MODS);
                if (modInfoList.isEmpty()) executeTask();
                else {
                    ContextExecutor.executeTaskWithAllContext(context ->
                        com.endiq.turtlelauncher.feature.mod.ModAutoMaintenance.runForVersion(
                            context, mVersion, modInfoList, () ->
                                new ModChecker().check(context, modInfoList, modCheckResult -> {
                                    mVersion.setModCheckResult(modCheckResult);
                                    executeTask();
                                    return null;
                                })
                        )
                    );
                }
            }
        });
    }

    @Override
    public void onDownloadFailed(Throwable throwable) {
        Tools.showErrorRemote(mErrorString, throwable);
    }

    @Override
    public void executeWithActivity(Activity activity) {
        try {
            Intent gameStartIntent = createGameStartIntent(activity);
            // TurtleLauncher: deprioritize (not cancel) the shared launcher task pool so it
            // stops competing with Minecraft for CPU. No-op if getQuitLauncher() then kills
            // this process right after - nothing left running to deprioritize at that point.
            com.endiq.turtlelauncher.task.TaskExecutors.setGameSessionActive(true);
            // TurtleLauncher: Background Services (item 20) - same hook point, handles
            // everything setGameSessionActive(true) above doesn't (animation pausing,
            // update-checker pausing, indexing pausing, RAM trim). See its class doc.
            com.endiq.turtlelauncher.feature.turtle.BackgroundServiceManager.onGameSessionStart(activity);
            // TurtleLauncher: Shizuku - apply the privileged keep-alive tweaks (Android 14's
            // phantom-process limit above all) BEFORE the game process is forked, since the
            // limit applies to processes forked by this app and killing the game mid-session
            // is exactly what it does. Fire-and-forget on its own thread: Shizuku is
            // optional, so this must never delay or block the launch. See ShizukuActions.
            com.endiq.turtlelauncher.feature.shizuku.ShizukuActions.applyBeforeLaunchIfEnabled();
            activity.startActivity(gameStartIntent);
            if (AllSettings.getQuitLauncher().getValue()) {
                activity.finish();
                android.os.Process.killProcess(android.os.Process.myPid()); //You should kill yourself, NOW!
            }
        } catch (Throwable e) {
            Tools.showError(activity.getBaseContext(), e);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        Intent gameStartIntent = createGameStartIntent(context);
        // Since the game is a separate process anyway, it does not matter if it gets invoked
        // from somewhere other than the launcher activity.
        // The only problem may arise if the launcher starts doing something when the user starts the notification.
        // So, the notification is automatically removed once there are tasks ongoing in the ProgressKeeper
        NotificationUtils.sendBasicNotification(context,
                R.string.notif_download_finished,
                R.string.notif_download_finished_desc,
                gameStartIntent,
                NotificationUtils.PENDINGINTENT_CODE_GAME_START,
                NotificationUtils.NOTIFICATION_ID_GAME_START
        );
        // You should keep yourself safe, NOW!
        // otherwise android does weird things...
    }
}
