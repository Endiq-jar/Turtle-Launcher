package com.endiq.turtlelauncher.utils.file

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.getFileNameWithoutExtension
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicLong


class FileCopyHandler @JvmOverloads constructor(
    mContext: Context,
    private val mPasteType: PasteFile.PasteType,
    private val mSelectedFiles: List<File>,
    private val mRoot: File,
    private val mTarget: File,
    private val mFileExtensionGetter: FileExtensionGetter?,
    private val endTask: Task<*>,
    // Auto-replace: when pasting over an existing file/folder with the same name, overwrite it
    // instead of creating "xxx (1)" copies.
    private val autoReplace: Boolean = true
) : FileHandler(mContext), FileSearchProgress {
    private val foundFiles = mutableMapOf<File, File>()
    private val totalFileSize = AtomicLong(0)
    private val fileSize = AtomicLong(0)
    private val fileCount = AtomicLong(0)

    fun start() {
        super.start(this)
    }

    // Read file sizes safely: while scanning, files may be deleted concurrently or turn out
    // to be broken symlinks,
    // FileUtils.sizeOf throws in these cases; catch it and fall back to 0 so an uncaught
    // exception cannot crash the app.
    private fun safeSizeOf(file: File): Long =
        runCatching { FileUtils.sizeOf(file) }.getOrElse { e ->
            Logging.e("FileCopyHandler", "Failed to get size of ${file.absolutePath}", e)
            0L
        }

    private fun addFile(file: File) {
        fileCount.incrementAndGet()
        fileSize.addAndGet(safeSizeOf(file))
        // current file - target file
        foundFiles [file] = getNewDestination(file, getTargetFile(file), mFileExtensionGetter?.onGet(file))
    }

    private fun addDirectory(directory: File) {
        if (directory.isFile) {
            addFile(directory)
        } else {
            directory.listFiles()?.let { files ->
                if (files.isEmpty()) {
                    addFile(directory)
                } else {
                    files.forEach { file ->
                        if (file.isFile) addFile(file)
                        else if (file.isDirectory) addDirectory(file)
                    }
                }
            }
        }
    }

    private fun getTargetFile(file: File): File {
        return File(file.absolutePath.replace(mRoot.absolutePath, mTarget.absolutePath).removeSuffix(file.name))
    }

    // If a file with the same name already exists at the target:
    //  - with autoReplace enabled the target file/folder is replaced outright (the old one is
    //    deleted so the following copy/move writes straight through)
    //  - otherwise a numeric suffix is added to the target name so files are not overwritten
    private fun getNewDestination(sourceFile: File, targetDir: File, fileExtension: String?): File {
        var destFile = File(targetDir, sourceFile.name)
        if (!destFile.exists()) return destFile

        if (autoReplace) {
            // Same file: nothing to do (avoids deleting the source when copying in place).
            if (destFile.canonicalPath != sourceFile.canonicalPath) {
                runCatching { FileUtils.deleteQuietly(destFile) }
                    .onFailure { e -> Logging.e("FileCopyHandler", "Failed to auto-replace ${destFile.absolutePath}", e) }
            }
            return destFile
        }

        var extension: String? = fileExtension
        val fileNameWithoutExt = getFileNameWithoutExtension(sourceFile.name, extension)
        extension ?: run {
            val dotIndex = sourceFile.name.lastIndexOf('.')
            extension = if (dotIndex == -1) "" else sourceFile.name.substring(dotIndex)
        }
        var proposedFileName: String
        var counter = 1
        while (destFile.exists()) {
            proposedFileName = "$fileNameWithoutExt ($counter)$extension"
            destFile = File(targetDir, proposedFileName)
            counter++
        }
        return destFile
    }

    override fun searchFilesToProcess() {
        mSelectedFiles.forEach {
            currentTask?.let { task -> if (task.isCancelled) return@forEach }

            runCatching {
                if (it.isFile) addFile(it)
                else if (it.isDirectory) addDirectory(it)
            }.onFailure { e -> Logging.e("FileCopyHandler", "Failed to scan ${it.absolutePath}", e) }
        }
        currentTask?.let { task -> if (task.isCancelled) return }
        totalFileSize.set(fileSize.get())
    }

    override fun processFile() {
        Logging.i("FileCopyHandler", "Copy files (total files: $fileCount, to ${mTarget.absolutePath})")
        foundFiles.entries.parallelStream().forEach { (currentFile, targetFile) ->
            currentTask?.let { task -> if (task.isCancelled) return@forEach }

            // A single file failure must not abort the whole copy/move flow, and certainly must
            // not crash the app through an uncaught exception.
            runCatching {
                fileSize.addAndGet(-safeSizeOf(currentFile))
                fileCount.decrementAndGet()
                targetFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
                when (mPasteType) {
                    PasteFile.PasteType.COPY -> FileTools.copyFile(currentFile, targetFile)
                    else -> FileTools.moveFile(currentFile, targetFile)
                }
            }.onFailure { e -> Logging.e("FileCopyHandler", "Failed to process ${currentFile.absolutePath}", e) }
        }
        currentTask?.let { task -> if (task.isCancelled) return }
        if (mPasteType == PasteFile.PasteType.MOVE) {
            mSelectedFiles.forEach {
                runCatching { FileUtils.deleteQuietly(it) }
                    .onFailure { e -> Logging.e("FileCopyHandler", "Failed to delete source ${it.absolutePath}", e) }
            }
        }
    }

    override fun getCurrentFileCount() = fileCount.get()

    override fun getTotalSize() = totalFileSize.get()

    override fun getPendingSize() = fileSize.get()

    override fun onEnd() {
        endTask.execute()
    }

    interface FileExtensionGetter {
        fun onGet(file: File?): String?
    }
}
