package com.endiq.turtlelauncher.feature.mod.modloader

import com.kdt.mcgui.ProgressLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.version.install.InstallTask
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools.DownloaderFeedback
import net.kdt.pojavlaunch.modloaders.OFDownloadPageScraper
import net.kdt.pojavlaunch.modloaders.OptiFineUtils.OptiFineVersion
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.utils.DownloadUtils
import java.io.File
import java.io.IOException

class OptiFineDownloadTask(
    private val mOptiFineVersion: OptiFineVersion
) : InstallTask, DownloaderFeedback {
    private val mDestinationFile = File(PathManager.DIR_CACHE, "optifine-installer.jar")

    @Throws(IOException::class)
    override fun run(customName: String): File? {
        ProgressKeeper.submitProgress(
            ProgressLayout.INSTALL_RESOURCE,
            0,
            R.string.mod_download_progress,
            mOptiFineVersion.versionName
        )
        val downloadUrl = OFDownloadPageScraper.run(mOptiFineVersion.downloadUrl) ?: return null
        DownloadUtils.downloadFileMonitored(
            downloadUrl, mDestinationFile, ByteArray(8192),
            this
        )
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)

        return mDestinationFile
    }

    override fun updateProgress(curr: Long, max: Long) {
        val progress100 = ((curr.toFloat() / max.toFloat()) * 100f).toInt()
        ProgressKeeper.submitProgress(
            ProgressLayout.INSTALL_RESOURCE,
            progress100,
            R.string.mod_optifine_progress,
            mOptiFineVersion.versionName
        )
    }
}
