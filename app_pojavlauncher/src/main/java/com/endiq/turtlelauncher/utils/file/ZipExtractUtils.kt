package com.endiq.turtlelauncher.utils.file

import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * TurtleLauncher – File Manager Zip / Extract utilities.
 *
 * All operations are synchronous and intended to be called from a
 * background task (Task.runTask { ... }).
 */
object ZipExtractUtils {

    private const val BUFFER = 8192

    // ── Compress ────────────────────────────────────────────────────────────

    /**
     * Compress one or more files/folders into a .zip archive.
     *
     * @param sources  files or directories to include
     * @param destZip  output .zip file (will be created / overwritten)
     * @param progress optional 0–100 callback
     */
    @JvmStatic
    @Throws(Exception::class)
    fun compress(
        sources: List<File>,
        destZip: File,
        progress: ((Int) -> Unit)? = null
    ) {
        Logging.i("ZipExtractUtils", "Compressing ${sources.size} item(s) → ${destZip.absolutePath}")

        destZip.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(destZip).buffered(BUFFER)).use { zos ->
            val total = countFiles(sources).coerceAtLeast(1)
            var done = 0

            for (src in sources) {
                if (src.isDirectory) {
                    addDirectory(src, src.name + "/", zos) { done++; progress?.invoke((done * 100) / total) }
                } else {
                    addFile(src, src.name, zos)
                    done++
                    progress?.invoke((done * 100) / total)
                }
            }
        }

        Logging.i("ZipExtractUtils", "Compressed → ${destZip.length()} bytes")
    }

    // ── Extract ─────────────────────────────────────────────────────────────

    /**
     * Extract a .zip file into [destDir].
     *
     * @param zipFile  source .zip
     * @param destDir  destination directory (created if missing)
     * @param progress optional 0–100 callback
     */
    @JvmStatic
    @Throws(Exception::class)
    fun extract(
        zipFile: File,
        destDir: File,
        progress: ((Int) -> Unit)? = null
    ) {
        Logging.i("ZipExtractUtils", "Extracting ${zipFile.absolutePath} → ${destDir.absolutePath}")

        destDir.mkdirs()

        // Count entries for progress
        val total = countZipEntries(zipFile).coerceAtLeast(1)
        var done = 0

        ZipInputStream(FileInputStream(zipFile).buffered(BUFFER)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = resolveEntry(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).buffered(BUFFER).use { fos ->
                        val buf = ByteArray(BUFFER)
                        var len: Int
                        while (zis.read(buf).also { len = it } >= 0) {
                            fos.write(buf, 0, len)
                        }
                    }
                    // Preserve last-modified if the entry has it
                    if (entry.time > 0) outFile.setLastModified(entry.time)
                }
                done++
                progress?.invoke((done * 100) / total)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Logging.i("ZipExtractUtils", "Extracted $done entries")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun addDirectory(
        dir: File,
        prefix: String,
        zos: ZipOutputStream,
        onFile: () -> Unit
    ) {
        val children = dir.listFiles() ?: return
        // emit an explicit directory entry so empty dirs survive
        zos.putNextEntry(ZipEntry(prefix))
        zos.closeEntry()
        for (child in children) {
            if (child.isDirectory) {
                addDirectory(child, prefix + child.name + "/", zos, onFile)
            } else {
                addFile(child, prefix + child.name, zos)
                onFile()
            }
        }
    }

    private fun addFile(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).buffered(BUFFER).use { fis ->
            val entry = ZipEntry(entryName).apply { time = file.lastModified() }
            zos.putNextEntry(entry)
            val buf = ByteArray(BUFFER)
            var len: Int
            while (fis.read(buf).also { len = it } >= 0) zos.write(buf, 0, len)
            zos.closeEntry()
        }
    }

    private fun countFiles(sources: List<File>): Int {
        var count = 0
        for (f in sources) count += if (f.isDirectory) countDir(f) else 1
        return count
    }

    private fun countDir(dir: File): Int {
        var c = 0
        dir.walkTopDown().forEach { if (it.isFile) c++ }
        return c
    }

    private fun countZipEntries(zipFile: File): Int {
        var c = 0
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                while (zis.nextEntry != null) { c++; zis.closeEntry() }
            }
        } catch (_: Exception) {}
        return c
    }

    /**
     * Safely resolves a zip entry path against [destDir], preventing
     * path-traversal attacks (zip-slip).
     */
    private fun resolveEntry(destDir: File, entryName: String): File {
        val resolved = File(destDir, entryName).canonicalFile
        val base = destDir.canonicalFile
        require(resolved.startsWith(base)) {
            "Zip slip detected for entry: $entryName"
        }
        return resolved
    }

    /**
     * Suggests a unique output zip name next to [source].
     * e.g.  "mods" → "mods.zip", then "mods_1.zip", etc.
     */
    @JvmStatic
    fun suggestZipName(source: File): File {
        val parent = source.parentFile ?: source
        val base   = source.name
        var candidate = File(parent, "$base.zip")
        var i = 1
        while (candidate.exists()) {
            candidate = File(parent, "${base}_$i.zip")
            i++
        }
        return candidate
    }

    /**
     * Suggests a unique output folder name next to [zipFile] for extraction.
     * e.g.  "archive.zip" → "archive/", then "archive_1/", etc.
     */
    @JvmStatic
    fun suggestExtractDir(zipFile: File): File {
        val parent  = zipFile.parentFile ?: zipFile
        val base    = zipFile.nameWithoutExtension
        var candidate = File(parent, base)
        var i = 1
        while (candidate.exists()) {
            candidate = File(parent, "${base}_$i")
            i++
        }
        return candidate
    }
}
