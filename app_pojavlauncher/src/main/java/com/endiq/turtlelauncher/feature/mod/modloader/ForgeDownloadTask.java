package com.endiq.turtlelauncher.feature.mod.modloader;

import androidx.annotation.NonNull;

import com.endiq.mcgui.ProgressLayout;
import net.kdt.pojavlaunch.R;
import com.endiq.turtlelauncher.feature.version.install.InstallTask;
import com.endiq.turtlelauncher.utils.path.PathManager;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.ForgeUtils;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ForgeDownloadTask implements InstallTask, Tools.DownloaderFeedback {
    private String mDownloadUrl;
    private String mFullVersion;
    private String mLoaderVersion;
    private String mGameVersion;

    public ForgeDownloadTask(String forgeVersion) {
        this.mDownloadUrl = ForgeUtils.getInstallerUrl(forgeVersion);
        this.mFullVersion = forgeVersion;
    }

    public ForgeDownloadTask(String gameVersion, String loaderVersion) {
        this.mLoaderVersion = loaderVersion;
        this.mGameVersion = gameVersion;
    }

    @Override
    public File run(@NonNull String customName) throws Exception {
        File outputFile = null;
        if (determineDownloadUrl()) {
            outputFile = downloadForge();
        }
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE);
        return outputFile;
    }

    @Override
    public void updateProgress(long curr, long max) {
        int progress100 = (int)(((float)curr / (float)max)*100f);
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, progress100, R.string.mod_download_progress, mFullVersion);
    }

    private File downloadForge() throws Exception {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_download_progress, mFullVersion);
        File destinationFile = new File(PathManager.DIR_CACHE, "forge-installer.jar");
        byte[] buffer = new byte[8192];
        DownloadUtils.downloadFileMonitored(mDownloadUrl, destinationFile, buffer, this);
        return destinationFile;
    }

    public boolean determineDownloadUrl() throws Exception {
        if (mDownloadUrl != null && mFullVersion != null) return true;
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_forge_searching);
        if (!findVersion()) {
            throw new IOException("Version not found");
        }
        return true;
    }

    public boolean findVersion() throws Exception {
        List<String> forgeVersions = ForgeUtils.downloadForgeVersions(false);
        if(forgeVersions == null) return false;
        String versionStart = mGameVersion+"-"+mLoaderVersion;
        for(String versionName : forgeVersions) {
            if(!versionName.startsWith(versionStart)) continue;
            mFullVersion = versionName;
            mDownloadUrl = ForgeUtils.getInstallerUrl(mFullVersion);
            return true;
        }
        return false;
    }

}
