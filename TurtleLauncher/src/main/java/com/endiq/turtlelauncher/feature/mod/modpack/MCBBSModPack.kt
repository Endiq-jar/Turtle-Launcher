package com.endiq.turtlelauncher.feature.mod.modpack

import android.content.Context
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.item.ModLoaderWrapper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.models.MCBBSPackMeta
import com.endiq.turtlelauncher.feature.mod.models.MCBBSPackMeta.MCBBSAddons
import com.endiq.turtlelauncher.feature.mod.modpack.install.ModPackUtils
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.ProgressDialog
import com.endiq.turtlelauncher.utils.file.FileTools
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.FileUtils
import net.kdt.pojavlaunch.utils.ZipUtils
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile


class MCBBSModPack(private val context: Context, private val zipFile: File?) {
    private var installDialog: ProgressDialog? = null
    private var isCanceled = false

    @Throws(IOException::class)
    fun install(versionFolder: File): ModLoaderWrapper? {
        zipFile?.let {
            ZipFile(this.zipFile).use { modpackZipFile ->
                val mcbbsPackMeta = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "mcbbs.packmeta")),
                    MCBBSPackMeta::class.java
                )
                if (!ModPackUtils.verifyMCBBSPackMeta(mcbbsPackMeta)) {
                    Logging.i("MCBBSModPack", "manifest verification failed")
                    return null
                }

                initDialog()

                val overridesDir = "overrides" + File.separatorChar
                val dirNameLen = overridesDir.length

                val fileCounters = AtomicInteger() // file counter
                // A manifest without a files list (or with null entries) used to NPE
                // here - skip straight to loader detection instead.
                val files = mcbbsPackMeta.files ?: return createInfo(mcbbsPackMeta.addons)
                val length = files.size

                for (file in files) {
                    if (isCanceled) {
                        cancel(versionFolder)
                        return null
                    }
                    if (file == null || file.path.isNullOrEmpty()) continue

                    val entry = modpackZipFile.getEntry(overridesDir + file.path)
                    if (entry != null) {
                        val entryName = entry.name
                        val zipDestination = File(versionFolder, entryName.substring(dirNameLen))
                        // Zip-slip guard: a malicious manifest entry ("../../evil") must
                        // not let the extraction escape versionFolder.
                        if (!zipDestination.canonicalFile.path.startsWith(versionFolder.canonicalFile.path + File.separator)) {
                            Logging.w("MCBBSModPack", "Skipping zip-slip entry: ${file.path}")
                            continue
                        }
                        if (zipDestination.exists() && !file.force) continue

                        val fileHash = FileTools.calculateFileHash(modpackZipFile.getInputStream(entry), "SHA-1")
                        val equals = file.hash == fileHash

                        if (equals) {
                            // If the hashes match, copy the file (an existing file is only
							// overwritten when the "force" setting says so).
                            FileUtils.ensureParentDirectory(zipDestination)

                            modpackZipFile.getInputStream(entry).use { entryInputStream ->
                                Files.newOutputStream(zipDestination.toPath())
                                    .use { outputStream ->
                                        IOUtils.copy(entryInputStream, outputStream)
                                    }
                            }
                            val fileCount = fileCounters.getAndIncrement()
                            TaskExecutors.runInUIThread {
                                installDialog?.updateText(
                                    context.getString(
                                        R.string.select_modpack_local_installing_files,
                                        fileCount,
                                        length
                                    )
                                )
                                installDialog?.updateProgress(
                                    fileCount.toDouble(),
                                    length.toDouble()
                                )
                            }
                        }
                    }
                }

                closeDialog()
                return createInfo(mcbbsPackMeta.addons)
            }
        }
        return null
    }

    private fun initDialog() {
        TaskExecutors.runInUIThread {
            installDialog = ProgressDialog(context) {
                isCanceled = true
                true
            }
            installDialog?.show()
        }
    }

    private fun closeDialog() {
        TaskExecutors.runInUIThread { installDialog?.dismiss() }
    }

    private fun cancel(instanceDestination: File) {
        org.apache.commons.io.FileUtils.deleteQuietly(instanceDestination)
    }

    private fun createInfo(addons: Array<MCBBSAddons?>): ModLoaderWrapper? {
        var version = ""
        var modLoader = ""
        var modLoaderVersion = ""
        // Was `0..addons.size` (inclusive): the final iteration read one past the end
        // of the array (ArrayIndexOutOfBounds), and a null element crashed on the
        // non-null assertion before the null check below it was ever reached.
        for (addon in addons) {
            if (addon == null) continue
            if (addon.id == "game") {
                version = addon.version ?: ""
                continue
            }
            modLoader = addon.id ?: ""
            modLoaderVersion = addon.version ?: ""
            break
        }
        val modloader = when (modLoader) {
            "forge" -> ModLoader.FORGE
            "neoforge" -> ModLoader.NEOFORGE
            "fabric" -> ModLoader.FABRIC
            else -> return null
        }
        return ModLoaderWrapper(modloader, modLoaderVersion, version)
    }
}
