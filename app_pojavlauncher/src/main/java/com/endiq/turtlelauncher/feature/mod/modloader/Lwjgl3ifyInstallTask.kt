package com.endiq.turtlelauncher.feature.mod.modloader

import android.app.Activity
import com.endiq.turtlelauncher.feature.version.install.InstallTask
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyDownloadTask
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Turtle wrapper around the Amethyst [LWJGL3ifyDownloadTask].
 *
 * LWJGL3ify lets old (1.7.x-era) modded versions run on modern Java runtimes.
 * The task is self-contained: it downloads the jar, resolves dependencies,
 * creates its own instance/profile and registers it with the launcher.
 */
class Lwjgl3ifyInstallTask(
    private val activity: Activity,
    private val mod: LWJGL3ifyUtils.LWJGL3ifyMod
) : InstallTask {
    @Throws(Exception::class)
    override fun run(customName: String): File? {
        val latch = CountDownLatch(1)
        var error: Exception? = null
        val task = LWJGL3ifyDownloadTask(object : ModloaderDownloadListener {
            override fun onDownloadFinished(downloadedFile: File?) { latch.countDown() }
            override fun onDataNotAvailable() {
                error = Exception("LWJGL3ify download data not available")
                latch.countDown()
            }
            override fun onDownloadError(e: Exception) {
                error = e
                latch.countDown()
            }
        }, mod, activity)
        task.run()
        latch.await()
        error?.let { throw it }
        return null // self-installing; no installer jar for the surrounding flow
    }
}
