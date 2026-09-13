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
    //自动替换：粘贴时如果目标位置已存在同名文件/文件夹，直接覆盖替换，而不是生成"xxx (1)"这样的副本
    private val autoReplace: Boolean = true
) : FileHandler(mContext), FileSearchProgress {
    private val foundFiles = mutableMapOf<File, File>()
    private val totalFileSize = AtomicLong(0)
    private val fileSize = AtomicLong(0)
    private val fileCount = AtomicLong(0)

    fun start() {
        super.start(this)
    }

    //安全获取文件大小，文件在扫描/处理过程中可能被并发删除或是损坏的软链接，
    //FileUtils.sizeOf 在这些情况下会抛出异常；这里捕获后回退为0，避免造成未捕获异常使App崩溃
    private fun safeSizeOf(file: File): Long =
        runCatching { FileUtils.sizeOf(file) }.getOrElse { e ->
            Logging.e("FileCopyHandler", "Failed to get size of ${file.absolutePath}", e)
            0L
        }

    private fun addFile(file: File) {
        fileCount.incrementAndGet()
        fileSize.addAndGet(safeSizeOf(file))
        //当前文件 - 目标文件
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

    //如果目标地点已存在同名文件：
    //  - 开启自动替换(autoReplace)时，直接覆盖目标文件/文件夹（删除旧的，让后续copy/move直接写入）
    //  - 否则，将目标文件的文件名加上数字标识，防止文件被覆盖
    private fun getNewDestination(sourceFile: File, targetDir: File, fileExtension: String?): File {
        var destFile = File(targetDir, sourceFile.name)
        if (!destFile.exists()) return destFile

        if (autoReplace) {
            //同一个文件，不需要做任何事（避免误删源文件本身，比如复制到原地）
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

            //单个文件的处理失败不应该终止整个复制/移动流程，更不应该导致未捕获异常使App崩溃
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
