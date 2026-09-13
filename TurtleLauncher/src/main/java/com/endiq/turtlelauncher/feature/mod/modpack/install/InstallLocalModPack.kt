package com.endiq.turtlelauncher.feature.mod.modpack.install

import android.content.Context
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.download.item.ModLoaderWrapper
import com.endiq.turtlelauncher.feature.download.platform.curseforge.CurseForgeModPackInstallHelper
import com.endiq.turtlelauncher.feature.download.platform.modrinth.ModrinthModPackInstallHelper
import com.endiq.turtlelauncher.feature.download.platform.multimc.MultiMCModPackInstallHelper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.models.MCBBSPackMeta
import com.endiq.turtlelauncher.feature.mod.modpack.MCBBSModPack
import com.endiq.turtlelauncher.feature.mod.modpack.install.ModPackUtils.ModPackEnum
import com.endiq.turtlelauncher.feature.version.VersionConfig
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.ZipUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.zip.ZipFile


class InstallLocalModPack {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun installModPack(
            context: Context,
            type: ModPackEnum?,
            zipFile: File,
            customVersionName: String
        ): ModLoaderWrapper? {
            try {
                runCatching {
                    ZipFile(zipFile)
                }.getOrElse {
                    Logging.e("Install local ModPack", "This file doesn't seem to be a proper archive", it)
                    TaskExecutors.runInUIThread {
                        showUnSupportDialog(context)
                    }
                    return null
                }.use { modpackZipFile ->
                    val modLoader: ModLoaderWrapper?
                    val versionPath = VersionsManager.getVersionPath(customVersionName)

                    when (type) {
                        ModPackEnum.MCBBS -> {
                            val mcbbsEntry = modpackZipFile.getEntry("mcbbs.packmeta")

                            val mcbbsPackMeta = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(
                                    modpackZipFile.getInputStream(mcbbsEntry)
                                ), MCBBSPackMeta::class.java
                            )

                            modLoader = mcbbsModPack(context, zipFile, versionPath) ?: return null
                            VersionConfig.createIsolation(versionPath).apply {
                                setJavaArgs(StringUtils.insertSpace(null, *mcbbsPackMeta.launchInfo.javaArgument))
                            }.save()

                            return modLoader
                        }

                        ModPackEnum.MODRINTH -> {
                            modLoader = modrinthModPack(zipFile, versionPath) ?: return null
                            VersionConfig.createIsolation(versionPath).save()

                            return modLoader
                        }

                        ModPackEnum.CURSEFORGE -> {
                            // Unlike the others, a CurseForge install can legitimately return
                            // null here (createLoaderInfo() failing to parse an unrecognized
                            // loader id) while still having installed overrides successfully -
                            // that's still useful, so don't bail out before saving isolation.
                            modLoader = curseForgeModPack(context, zipFile, versionPath)
                            VersionConfig.createIsolation(versionPath).save()

                            return modLoader
                        }

                        ModPackEnum.MULTIMC -> {
                            // Same "null loader is still a useful result" tolerance as
                            // CurseForge above (a vanilla MultiMC instance, or one on a
                            // loader this launcher doesn't support, still gets its
                            // mods/saves/configs extracted).
                            modLoader = MultiMCModPackInstallHelper.installZip(zipFile, versionPath)
                            VersionConfig.createIsolation(versionPath).save()

                            return modLoader
                        }

                        ModPackEnum.GENERIC_ZIP -> {
                            // No manifest to read, so no loader to detect or mods to download -
                            // just unpack the whole zip into the new instance's root and let the
                            // person configure a loader afterward, same as any blank instance.
                            genericZipImport(zipFile, versionPath)
                            VersionConfig.createIsolation(versionPath).save()

                            return null
                        }

                        else -> {
                            TaskExecutors.runInUIThread {
                                showUnSupportDialog(context)
                            }
                            return null
                        }
                    }
                }
            } finally {
                FileUtils.deleteQuietly(zipFile) // delete the archive (usually small anyway)
            }
        }

        @JvmStatic
        fun showUnSupportDialog(context: Context) {
            TipDialog.Builder(context)
                .setTitle(R.string.generic_warning)
                .setMessage(R.string.select_modpack_local_not_supported) // warn via dialog
                .setWarning()
                .setShowCancel(true)
                .setShowConfirm(false)
                .showDialog()
        }

        @Throws(Exception::class)
        private fun modrinthModPack(
            zipFile: File,
            versionPath: File
        ): ModLoaderWrapper? {
            return ModrinthModPackInstallHelper.installZip(
                zipFile,
                versionPath
            )
        }

        @Throws(Exception::class)
        private fun curseForgeModPack(
            context: Context,
            zipFile: File,
            versionPath: File
        ): ModLoaderWrapper? {
            return CurseForgeModPackInstallHelper.installZip(context, zipFile, versionPath)
        }

        @Throws(Exception::class)
        private fun genericZipImport(zipFile: File, versionPath: File) {
            ZipFile(zipFile).use { zip ->
                ZipUtils.zipExtract(zip, "", versionPath)
            }
        }

        @Throws(Exception::class)
        private fun mcbbsModPack(context: Context, zipFile: File, versionPath: File): ModLoaderWrapper? {
            val mcbbsModPack = MCBBSModPack(context, zipFile)
            return mcbbsModPack.install(versionPath)
        }
    }
}
