package com.endiq.turtlelauncher.feature.mod.modloader;

import androidx.annotation.NonNull;

import com.endiq.mcgui.ProgressLayout;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome;
import com.endiq.turtlelauncher.feature.version.install.InstallTask;
import com.endiq.turtlelauncher.utils.path.PathManager;

import net.endiq.launcher.Tools;
import net.endiq.launcher.progresskeeper.ProgressKeeper;
import net.endiq.launcher.utils.DownloadUtils;
import net.endiq.launcher.utils.FileUtils;

import java.io.File;

public class FabricLikeDownloadTask implements InstallTask, Tools.DownloaderFeedback {
    private final FabricLikeUtils mUtils;
    private String mGameVersion = null;
    private String mLoaderVersion = null;

    public FabricLikeDownloadTask(FabricLikeUtils utils) {
        this.mUtils = utils;
    }

    public FabricLikeDownloadTask(FabricLikeUtils utils, String gameVersion, String loaderVersion) {
        this(utils);
        this.mGameVersion = gameVersion;
        this.mLoaderVersion = loaderVersion;
    }

    @Override
    public File run(@NonNull String customName) throws Exception {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_download_progress, mUtils.getName());
        File outputFile;
        if (mGameVersion == null && mLoaderVersion == null) {
            outputFile = downloadInstaller();
        }
        else {
            legacyInstall(customName);
            outputFile = null;
        }
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE);
        return outputFile;
    }

    private File downloadInstaller() throws Exception {
        File outputFile = new File(PathManager.DIR_CACHE, "fabric-installer.jar");

        String installerDownloadUrl = mUtils.getInstallerDownloadUrl();
        byte[] buffer = new byte[8192];
        DownloadUtils.downloadFileMonitored(installerDownloadUrl, outputFile, buffer, this);

        return outputFile;
    }

    // Quilt runs on JRE 17 and the JVM does not exit on its own afterwards.
    // Done this way for now to keep the automation simple.
    private void legacyInstall(String customName) throws Exception {
        String jsonString = DownloadUtils.downloadString(mUtils.createJsonDownloadUrl(mGameVersion, mLoaderVersion));

        File versionJsonDir = new File(ProfilePathHome.getVersionsHome(), customName);
        File versionJsonFile = new File(versionJsonDir, customName + ".json");
        FileUtils.ensureDirectory(versionJsonDir);
        Tools.write(versionJsonFile.getAbsolutePath(), jsonString);
    }

    @Override
    public void updateProgress(long curr, long max) {
        int progress100 = (int)(((float)curr / (float)max)*100f);
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, progress100, R.string.mod_download_progress, mUtils.getName());
    }
}
