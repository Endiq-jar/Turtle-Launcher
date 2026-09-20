package com.endiq.turtlelauncher.feature.mod.modloader;

import androidx.annotation.NonNull;

import com.endiq.mcgui.ProgressLayout;
import net.kdt.pojavlaunch.R;
import com.endiq.turtlelauncher.feature.version.install.InstallTask;
import com.endiq.turtlelauncher.utils.path.PathManager;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.CleanroomUtils;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;

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
    public void updateProgress(int curr, int max) {
        int progress100 = (int) (((float) curr / (float) max) * 100f);
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, progress100, R.string.mod_download_progress, mVersion);
    }
}
