package com.endiq.turtlelauncher.feature.version

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ModpackExporter {

    private val INCLUDED_TOP_LEVEL = setOf("mods", "config", "resourcepacks", "shaderpacks")
    private val INCLUDED_FILES = setOf("options.txt", "servers.dat")

    class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Exports [version]'s mods/config/options into a single zip, returning the resulting file. Runs synchronously — call off the UI thread. */
    @JvmStatic
    @Throws(ExportException::class)
    fun export(version: Version): File {
        val gameDir = version.getGameDir()
        if (!gameDir.isDirectory) {
            throw ExportException("Game directory does not exist: ${gameDir.absolutePath}")
        }

        val outputDir = File(PathManager.DIR_GAME_HOME, "exported_modpacks").apply { mkdirs() }
        val safeName = version.getVersionName().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputFile = File(outputDir, "${safeName}_modpack.zip")
        if (outputFile.exists()) outputFile.delete()

        try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                INCLUDED_TOP_LEVEL.forEach { dirName ->
                    val dir = File(gameDir, dirName)
                    if (dir.isDirectory) {
                        addDirectoryToZip(zos, dir, dirName)
                    }
                }
                INCLUDED_FILES.forEach { fileName ->
                    val file = File(gameDir, fileName)
                    if (file.isFile) {
                        addFileToZip(zos, file, fileName)
                    }
                }
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw ExportException("Failed while writing zip", e)
        }

        if (outputFile.length() == 0L) {
            outputFile.delete()
            throw ExportException("Nothing to export — no mods/config/options.txt found for this version")
        }

        Logging.i("ModpackExporter", "Exported modpack for ${version.getVersionName()} -> ${outputFile.absolutePath}")
        return outputFile
    }

    /**
     * Exports a Modrinth-compatible `.mrpack` file containing `modrinth.index.json` (with
     * sha1/sha512 hashes for every mod file) plus the `overrides/` tree for config/options.
     * The resulting file can be imported by any Modrinth-compatible launcher.
     */
    @JvmStatic
    @Throws(ExportException::class)
    fun exportMrpack(version: Version): File {
        val gameDir = version.getGameDir()
        if (!gameDir.isDirectory) throw ExportException("Game directory does not exist: ${gameDir.absolutePath}")

        val outputDir = File(PathManager.DIR_GAME_HOME, "exported_modpacks").apply { mkdirs() }
        val safeName = version.getVersionName().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputFile = File(outputDir, "${safeName}.mrpack")
        if (outputFile.exists()) outputFile.delete()

        val versionInfo = runCatching { version.getVersionInfo() }.getOrNull()
        val mcVersion = versionInfo?.minecraftVersion ?: "unknown"

        // Build modrinth.index.json files list from mods/
        val modsDir = File(gameDir, "mods")
        val indexFiles = mutableListOf<ModrinthIndex.ModrinthIndexFile>()
        if (modsDir.isDirectory) {
            modsDir.listFiles { f -> f.isFile && f.extension.equals("jar", true) }?.forEach { jar ->
                val sha1 = sha1Hex(jar)
                val sha512 = sha512Hex(jar)
                val file = ModrinthIndex.ModrinthIndexFile().apply {
                    path = "mods/${jar.name}"
                    downloads = emptyArray() // no CDN URL — bundled in overrides
                    fileSize = jar.length().toInt()
                    hashes = ModrinthIndex.ModrinthIndexFile.ModrinthIndexFileHashes().apply {
                        this.sha1 = sha1; this.sha512 = sha512
                    }
                }
                indexFiles.add(file)
            }
        }

        val index = ModrinthIndex().apply {
            formatVersion = 1
            game = "minecraft"
            versionId = "1.0.0"
            name = version.getVersionName()
            summary = "Exported by TurtleLauncher"
            files = indexFiles.toTypedArray()
            dependencies = buildMap {
                put("minecraft", mcVersion)
                versionInfo?.loaderInfo?.firstOrNull()?.let { loader ->
                    put(loader.name.lowercase(), loader.version ?: "")
                }
            }
        }

        try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                // modrinth.index.json
                zos.putNextEntry(ZipEntry("modrinth.index.json"))
                zos.write(Tools.GLOBAL_GSON.toJson(index).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // overrides/ — everything except mods (those are declared in the index)
                val overrideDirs = listOf("config", "resourcepacks", "shaderpacks")
                overrideDirs.forEach { dirName ->
                    File(gameDir, dirName).takeIf { it.isDirectory }?.let { dir ->
                        addDirectoryToZip(zos, dir, "overrides/$dirName")
                    }
                }
                listOf("options.txt", "servers.dat").forEach { name ->
                    File(gameDir, name).takeIf { it.isFile }?.let { addFileToZip(zos, it, "overrides/$name") }
                }

                // mods themselves go in overrides/mods so launchers that don't re-download can still use them
                if (modsDir.isDirectory) addDirectoryToZip(zos, modsDir, "overrides/mods")
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw ExportException("Failed while writing mrpack", e)
        }

        Logging.i("ModpackExporter", "Exported .mrpack for ${version.getVersionName()} -> ${outputFile.absolutePath}")
        return outputFile
    }

    private fun sha1Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { fis -> val buf = ByteArray(8192); var n: Int; while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha512Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-512")
        FileInputStream(file).use { fis -> val buf = ByteArray(8192); var n: Int; while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Zips every file under [sourceDir] recursively, with entry paths rooted at [entryPrefix].
     *  Skips anything ending in ".disabled" (TurtleLauncher's mod-disable convention, see
     *  ModUtils.DISABLE_JAR_FILE_SUFFIX) - a file the player explicitly turned off has no
     *  business being bundled into an export. */
    private fun addDirectoryToZip(zos: ZipOutputStream, sourceDir: File, entryPrefix: String) {
        val children = sourceDir.listFiles() ?: return
        children.forEach { child ->
            if (child.name.endsWith(".disabled", ignoreCase = true)) return@forEach
            val entryName = "$entryPrefix/${child.name}"
            if (child.isDirectory) {
                addDirectoryToZip(zos, child, entryName)
            } else if (child.isFile) {
                addFileToZip(zos, child, entryName)
            }
        }
    }

    /** Writes a single [file] into the zip under [entryName]. */
    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        BufferedInputStream(FileInputStream(file)).use { input ->
            zos.putNextEntry(ZipEntry(entryName))
            input.copyTo(zos)
            zos.closeEntry()
        }
    }
}
