package com.endiq.turtlelauncher.feature.version.install

import android.app.Activity
import com.endiq.mcgui.ProgressLayout
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.event.value.InstallGameEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.task.Task
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class GameInstaller(
    private val activity: Activity,
    installEvent: InstallGameEvent
) {
    private val realVersion: String = installEvent.minecraftVersion
    private val customVersionName: String = installEvent.customVersionName
    private val taskMap: Map<Addon, InstallTaskItem> = installEvent.taskMap
    private val targetVersionFolder = VersionsManager.getVersionPath(customVersionName)
    private val vanillaVersionFolder = VersionsManager.getVersionPath(realVersion)

    fun installGame() {
        Logging.i("Minecraft Downloader", "Start downloading the version: $realVersion")

        if (taskMap.isNotEmpty()) {
            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.download_install_download_file, 0, 0, 0)
        }

        val mcVersion = AsyncMinecraftDownloader.getListedVersion(realVersion)
        MinecraftDownloader().start(
            mcVersion,
            realVersion,
            object : AsyncMinecraftDownloader.DoneListener {
                override fun onDownloadDone() {
                    Task.runTask {
                        if (taskMap.isEmpty()) {
                            if (realVersion != customVersionName && VersionsManager.isVersionExists(realVersion)) {
                                // Locate the vanilla .json file; MinecraftDownloader already fetched it.
                                val vanillaJsonFile = File(vanillaVersionFolder, "${vanillaVersionFolder.name}.json")
                                if (vanillaJsonFile.exists() && vanillaJsonFile.isFile) {
                                    // If the vanilla .json file exists, just copy it over.
                                    FileUtils.copyFile(vanillaJsonFile, File(targetVersionFolder, "$customVersionName.json"))
                                }
                            }
                            // The ModLoader task is empty, so the pointless follow-up tasks are skipped for good.
                            return@runTask null
                        }

                        // Split mod and mod-loader tasks; the mod has to be installed first.
                        val modTask: MutableList<InstallTaskItem> = ArrayList()
                        val modloaderTask = AtomicReference<Pair<Addon, InstallTaskItem>>() // one ModLoader install at a time for now
                        taskMap.forEach { (addon, taskItem) ->
                            if (taskItem.isMod) modTask.add(taskItem)
                            else modloaderTask.set(Pair(addon, taskItem))
                        }

                        // Download the mod file.
                        modTask.forEach { task ->
                            Logging.i("Install Version", "Installing Mod: ${task.selectedVersion}")
                            val file = task.task.run(customVersionName)
                            val endTask = task.endTask
                            file?.let { endTask?.endTask(activity, it) }
                        }

                        modloaderTask.get()?.let { taskPair ->
                            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_download_progress, taskPair.first.addonName)

                            Logging.i("Install Version", "Installing ModLoader: ${taskPair.second.selectedVersion}")
                            val file = taskPair.second.task.run(customVersionName)
                            return@runTask Pair(file, taskPair.second)
                        }

                        null
                    }.ended ended@{ taskPair ->
                        taskPair?.let { pair ->
                            pair.first?.let {
                                pair.second.endTask?.endTask(activity, it)
                            }
                        }
                    }.onThrowable { e ->
                        Tools.showErrorRemote(e)
                    }.execute()
                }

                override fun onDownloadFailed(throwable: Throwable) {
                    Tools.showErrorRemote(throwable)
                    if (taskMap.isNotEmpty()) {
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
                    }
                }
            }
        )
    }
}