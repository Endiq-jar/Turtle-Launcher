package com.endiq.turtlelauncher.feature.download.platform.curseforge

import android.content.Context
import com.endiq.mcgui.ProgressLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.item.ModLoaderWrapper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.CurseForgeApi
import com.endiq.turtlelauncher.feature.mod.models.CurseForgeManifest
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import net.endiq.launcher.Tools
import net.endiq.launcher.modloaders.modpacks.api.ModDownloader
import net.endiq.launcher.progresskeeper.DownloaderProgressWrapper
import net.endiq.launcher.utils.ZipUtils
import java.io.File
import java.util.zip.ZipFile

class CurseForgeModPackInstallHelper {
    companion object {
        private const val TAG = "CurseForgeModPackInstallHelper"

        @Throws(Exception::class)
        fun installZip(context: Context, packFile: File, targetPath: File): ModLoaderWrapper? {
            ZipFile(packFile).use { zip ->
                val manifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(zip, "manifest.json")),
                    CurseForgeManifest::class.java
                )
                if (!verify(manifest)) {
                    Logging.i(TAG, "manifest verification failed")
                    return null
                }

                val apiKey = AllSettings.curseForgeApiKey.getValue()
                val files = manifest.files.orEmpty()
                val skipped = mutableListOf<CurseForgeManifest.ManifestFile>()

                if (files.isNotEmpty()) {
                    if (apiKey.isBlank()) {
                        skipped.addAll(files)
                        Logging.i(TAG, "No CurseForge API key configured - skipping ${files.size} mod download(s)")
                    } else {
                        val modDownloader = ModDownloader(targetPath, true)
                        val failedIds = java.util.Collections.synchronizedList(mutableListOf<CurseForgeManifest.ManifestFile>())

                        for (manifestFile in files) {
                            modDownloader.submitDownload {
                                val resolved = CurseForgeApi.resolveFile(manifestFile.projectID, manifestFile.fileID, apiKey)
                                if (resolved == null) {
                                    failedIds.add(manifestFile)
                                    return@submitDownload null
                                }
                                ModDownloader.FileInfo(resolved.downloadUrl, "mods/${resolved.fileName}", resolved.sha1)
                            }
                        }

                        modDownloader.awaitFinish(
                            DownloaderProgressWrapper(R.string.modpack_download_downloading_mods, ProgressLayout.INSTALL_RESOURCE)
                        )
                        skipped.addAll(failedIds)
                    }
                }

                ProgressLayout.setProgress(ProgressLayout.INSTALL_RESOURCE, 0, R.string.modpack_download_applying_overrides, 1, 1)
                val overridesFolder = manifest.overrides?.takeIf { it.isNotBlank() } ?: "overrides"
                ZipUtils.zipExtract(zip, "$overridesFolder/", targetPath)

                if (skipped.isNotEmpty()) {
                    TaskExecutors.runInUIThread { showSkippedModsDialog(context, skipped.size, apiKey.isBlank()) }
                }

                return createLoaderInfo(manifest)
            }
        }

        private fun verify(manifest: CurseForgeManifest?): Boolean {
            manifest ?: return false
            if (manifest.manifestType != "minecraftModpack") return false
            if (manifest.minecraft?.version == null) return false
            return true
        }

        private fun createLoaderInfo(manifest: CurseForgeManifest): ModLoaderWrapper? {
            val mcVersion = manifest.minecraft.version
            // "primary" is the modpack's actual loader; CurseForge allows extra non-primary
            // entries for cases we don't need to handle here.
            val loaderEntry = manifest.minecraft.modLoaders?.firstOrNull { it.primary }
                ?: manifest.minecraft.modLoaders?.firstOrNull()
                ?: return null

            // id is "<loader>-<version>", e.g. "forge-47.2.0" or "fabric-0.15.11"
            val dashIndex = loaderEntry.id.indexOf('-')
            if (dashIndex <= 0) return null
            val loaderName = loaderEntry.id.substring(0, dashIndex)
            val loaderVersion = loaderEntry.id.substring(dashIndex + 1)
            if (loaderVersion.isBlank()) return null

            val loader = when (loaderName.lowercase()) {
                "forge" -> ModLoader.FORGE
                "neoforge" -> ModLoader.NEOFORGE
                "fabric" -> ModLoader.FABRIC
                "quilt" -> ModLoader.QUILT
                else -> {
                    Logging.i(TAG, "Unrecognized CurseForge loader id: ${loaderEntry.id}")
                    return null
                }
            }
            return ModLoaderWrapper(loader, loaderVersion, mcVersion)
        }

        private fun showSkippedModsDialog(context: Context, count: Int, noKeyConfigured: Boolean) {
            val message = if (noKeyConfigured) {
                context.getString(R.string.curseforge_import_no_api_key, count)
            } else {
                context.getString(R.string.curseforge_import_mods_failed, count)
            }
            TipDialog.Builder(context)
                .setTitle(R.string.curseforge_import_partial_title)
                .setMessage(message)
                .setWarning()
                .setShowCancel(true)
                .setShowConfirm(false)
                .showDialog()
        }
    }
}
