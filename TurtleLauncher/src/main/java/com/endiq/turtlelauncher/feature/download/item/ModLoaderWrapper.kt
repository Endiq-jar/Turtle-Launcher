package com.endiq.turtlelauncher.feature.download.item

import android.content.Context
import android.content.Intent
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.mod.modloader.NeoForgeDownloadTask
import com.endiq.turtlelauncher.feature.version.install.InstallTask
import net.endiq.launcher.JavaGUILauncherActivity
import com.endiq.turtlelauncher.feature.mod.modloader.FabricLikeUtils
import com.endiq.turtlelauncher.feature.mod.modloader.ForgeDownloadTask
import com.endiq.turtlelauncher.feature.version.install.InstallArgsUtils
import java.io.File

class ModLoaderWrapper(
    val modLoader: ModLoader,
    val modLoaderVersion: String,
    val minecraftVersion: String
) {
    val versionId: String?
        /**
         * Get the Version ID (the name of the mod loader in the versions/ folder)
         * @return the Version ID as a string
         */
        get() = when (modLoader) {
            ModLoader.FORGE -> "$minecraftVersion-forge-$modLoaderVersion"
            ModLoader.NEOFORGE -> "neoforge-$modLoaderVersion"
            ModLoader.FABRIC -> "fabric-loader-$modLoaderVersion-$minecraftVersion"
            ModLoader.QUILT -> "quilt-loader-$modLoaderVersion-$minecraftVersion"
            else -> null
        }

    val nameById: String?
        /**
         * Obtain the name of the ModLoader based on the Id value stored internally
         * @return ModLoader Name
         */
        get() = when (modLoader) {
            ModLoader.FORGE -> "Forge"
            ModLoader.NEOFORGE -> "NeoForge"
            ModLoader.FABRIC -> "Fabric"
            ModLoader.QUILT -> "Quilt"
            else -> null
        }

    /**
     * Get the Task that needs to run in order to download the mod loader.
     * The task will also install the mod loader if it does not require GUI installation
     * @return the task Runnable that needs to be ran
     */
    fun getDownloadTask(): InstallTask? {
        return when (modLoader) {
            ModLoader.FORGE -> ForgeDownloadTask(
                minecraftVersion,
                modLoaderVersion
            )

            ModLoader.NEOFORGE -> NeoForgeDownloadTask(
                modLoaderVersion
            )

            ModLoader.FABRIC -> FabricLikeUtils.FABRIC_UTILS.getDownloadTask(
                minecraftVersion,
                modLoaderVersion
            )

            ModLoader.QUILT -> FabricLikeUtils.QUILT_UTILS.getDownloadTask(
                minecraftVersion,
                modLoaderVersion
            )

            else -> null
        }
    }

    /**
     * Get the Intent to start the graphical installation of the mod loader.
     * This method should only be ran after the download task of the specified mod loader finishes.
     * This method returns null if the mod loader does not require GUI installation
     * @param context the package resolving Context (can be the base context)
     * @param modInstallerJar the JAR file of the mod installer, provided by ModloaderDownloadListener after the installation
     * @param customName custom name for the installed version
     * finishes.
     * @return the Intent which the launcher needs to start in order to install the mod loader
     */
    @Throws(Throwable::class)
    fun getInstallationIntent(context: Context?, modInstallerJar: File, customName: String): Intent? {
        val baseIntent = Intent(context, JavaGUILauncherActivity::class.java)
        when (modLoader) {
            ModLoader.FORGE -> {
                InstallArgsUtils(minecraftVersion, versionId ?: return null).setForge(baseIntent, modInstallerJar, customName)
                return baseIntent
            }

            ModLoader.NEOFORGE -> {
                InstallArgsUtils(minecraftVersion, versionId ?: return null).setNeoForge(baseIntent, modInstallerJar, customName)
                return baseIntent
            }

            ModLoader.FABRIC -> {
                InstallArgsUtils(minecraftVersion, modLoaderVersion).setFabric(baseIntent, modInstallerJar, customName)
                return baseIntent
            }

            ModLoader.QUILT -> return null
            else -> return null
        }
    }
}
