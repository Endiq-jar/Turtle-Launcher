package com.endiq.turtlelauncher.feature.mod.modloader

import com.endiq.mcgui.ProgressLayout
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.feature.download.item.VersionItem
import com.endiq.turtlelauncher.feature.version.install.InstallTask
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.utils.DownloadUtils
import java.io.File

class FabricLikeApiModDownloadTask(private val fileName: String, private val versionItem: VersionItem) : InstallTask, Tools.DownloaderFeedback {
    @Throws(Exception::class)
    override fun run(customName: String): File {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_download_progress, versionItem.fileName)
        val destinationFile = File(PathManager.DIR_CACHE, "$fileName.jar")
        DownloadUtils.downloadFileMonitored(versionItem.fileUrl, destinationFile, ByteArray(8192), this)
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
        return destinationFile
    }

    override fun updateProgress(curr: Int, max: Int) {
        val progress100 = ((curr.toFloat() / max.toFloat()) * 100f).toInt()
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, progress100, R.string.mod_download_progress, versionItem.fileName)
    }
}