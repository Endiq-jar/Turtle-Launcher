package com.endiq.turtlelauncher.feature.mod.modloader

import com.kdt.mcgui.ProgressLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.mod.ModrinthDirectApi
import com.endiq.turtlelauncher.feature.version.install.InstallTask
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import java.io.File
import java.io.IOException

/**
 * TurtleLauncher: backs InstallExtrasDialog's "Turtle Client" toggle. Fully automatic -
 * no version picker, no manual URL: resolves the newest Modrinth "turtleclient" build
 * that actually supports the Minecraft version being installed (Turtle Client is a
 * Fabric-only mod, hence ModLoader.FABRIC below) and downloads it. The resulting jar is
 * handed to InstallGameFragment's existing moveFile()-into-mods EndTask, the same shape
 * FABRIC_API/QSL already use via organizeInstallationTasks().
 *
 * Reuses ModrinthDirectApi (already used by ModDependencyResolver/ModUpdateChecker for
 * this exact "best match for this game version" lookup) instead of a new API client.
 */
class TurtleClientDownloadTask(private val mcVersion: String) : InstallTask {
    companion object {
        private const val MODRINTH_SLUG = "turtleclient"
    }

    @Throws(Exception::class)
    override fun run(customName: String): File {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.mod_download_progress, "Turtle Client")

        val versionObj = ModrinthDirectApi.findCompatibleVersion(MODRINTH_SLUG, mcVersion, ModLoader.FABRIC)
            ?: throw IOException("No Turtle Client build is published for Minecraft $mcVersion yet.")
        val primaryFile = ModrinthDirectApi.primaryFileOf(versionObj)
            ?: throw IOException("Turtle Client's Modrinth listing for Minecraft $mcVersion has no downloadable file.")
        val url = primaryFile.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("Turtle Client's Modrinth file entry is missing a download URL.")
        val fileName = primaryFile.get("filename")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "TurtleClient-$mcVersion.jar"

        val destinationFile = File(PathManager.DIR_CACHE, fileName)
        ModrinthDirectApi.downloadTo(url, destinationFile)

        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
        return destinationFile
    }
}
