package com.endiq.turtlelauncher.feature.mod.modloader;

import androidx.annotation.NonNull;

import com.endiq.mcgui.ProgressLayout;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.version.install.InstallTask;
import com.endiq.turtlelauncher.utils.path.PathManager;

import net.endiq.launcher.Tools;
import net.endiq.launcher.modloaders.CleanroomUtils;
import net.endiq.launcher.progresskeeper.ProgressKeeper;
import net.endiq.launcher.utils.DownloadUtils;

import java.io.File;

/**
 * Downloads Cleanroom's real installer.jar - mirrors ForgeDownloadTask exactly, since
 * it's the same kind of file (a self-contained installer jar), just from GitHub
 * Releases instead of Forge's Maven repo. See CleanroomUtils for the version-fetch
 * side and the important caveat about launcher support.
 */
public class CleanroomDownloadTask implements InstallTask, Tools.DownloaderFeedback {
    private final String mVersion;
    private final String mDownloadUrl;

    public CleanroomDownloadTask(String cleanroomVersion) {
        this.mVersion = cleanroomVersion;
        this.mDownloadUrl = CleanroomUtils.getInstallerUrl(cleanroomVersion);
    }

    @Override
    public File run(@NonNull String customName) throws Exception {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_download_progress, mVersion);
        File destinationFile = new File(PathManager.DIR_CACHE, "cleanroom-installer.jar");
        byte[] buffer = new byte[8192];
        DownloadUtils.downloadFileMonitored(mDownloadUrl, destinationFile, buffer, this);
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE);
        return destinationFile;
    }

    @Override
    public void updateProgress(long curr, long max) {
        int progress100 = (int) (((float) curr / (float) max) * 100f);
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, progress100, R.string.mod_download_progress, mVersion);
    }
}
