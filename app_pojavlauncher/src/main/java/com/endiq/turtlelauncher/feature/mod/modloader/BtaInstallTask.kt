package com.endiq.turtlelauncher.feature.mod.modloader

import com.endiq.turtlelauncher.feature.version.install.InstallTask
import net.kdt.pojavlaunch.modloaders.BTADownloadTask
import net.kdt.pojavlaunch.modloaders.BTAUtils
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Turtle wrapper around the Amethyst [BTADownloadTask].
 *
 * BTA (Better Than Adventure!) is a self-contained installer: it downloads the
 * BTA client jar, writes the "bta-<version>" version JSON (inheriting b1.7.3)
 * and registers a launcher profile for it. Because of the "inheritsFrom"
 * relationship, the base Minecraft version (b1.7.3) should be installed too —
 * which is exactly what the surrounding version-install flow does.
 */
class BtaInstallTask(private val version: BTAUtils.BTAVersion) : InstallTask {
    @Throws(Exception::class)
    override fun run(customName: String): File? {
        val latch = CountDownLatch(1)
        var error: Exception? = null
        val task = BTADownloadTask(object : ModloaderDownloadListener {
            override fun onDownloadFinished(downloadedFile: File?) { latch.countDown() }
            override fun onDataNotAvailable() {
                error = Exception("BTA download data not available")
                latch.countDown()
            }
            override fun onDownloadError(e: Exception) {
                error = e
                latch.countDown()
            }
        }, version)
        task.run()
        latch.await()
        error?.let { throw it }
        return null // the task installs itself; no installer jar to hand to the flow
    }
}
