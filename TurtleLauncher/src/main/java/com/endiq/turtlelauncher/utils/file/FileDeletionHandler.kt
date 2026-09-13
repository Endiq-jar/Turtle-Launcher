package com.endiq.turtlelauncher.utils.file

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.task.Task
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class FileDeletionHandler(
    mContext: Context,
    private val mSelectedFiles: List<File>,
    private val endTask: Task<*>?
) : FileHandler(mContext), FileSearchProgress {
    private val foundFiles = mutableListOf<File>()
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
            Logging.e("FileDeletionHandler", "Failed to get size of ${file.absolutePath}", e)
            0L
        }

    private fun addFile(file: File) {
        foundFiles.add(file)
        fileCount.addAndGet(1)
        fileSize.addAndGet(safeSizeOf(file))
    }

    private fun addDirectory(directory: File) {
        runCatching {
            if (directory.isFile) addFile(directory)
            else if (directory.isDirectory) {
                directory.listFiles()?.forEach {
                    if (it.isFile) addFile(it)
                    else if (it.isDirectory) addDirectory(it)
                }
            }
        }.onFailure { e -> Logging.e("FileDeletionHandler", "Failed to scan ${directory.absolutePath}", e) }
    }

    override fun searchFilesToProcess() {
        mSelectedFiles.forEach {
            currentTask?.let { task -> if (task.isCancelled) return@forEach }

            runCatching {
                if (it.isFile) addFile(it)
                else if (it.isDirectory) addDirectory(it)
            }.onFailure { e -> Logging.e("FileDeletionHandler", "Failed to scan ${it.absolutePath}", e) }
        }
        currentTask?.let { task -> if (task.isCancelled) return }
        totalFileSize.set(fileSize.get())
    }

    override fun processFile() {
        Logging.i("FileDeletionHandler", "Delete files (total files: $fileCount)")
        foundFiles.parallelStream().forEach {
            currentTask?.let { task -> if (task.isCancelled) return@forEach }

            //单个文件的处理失败不应该终止整个删除流程，更不应该导致未捕获异常使App崩溃
            runCatching {
                fileSize.addAndGet(-safeSizeOf(it))
                fileCount.getAndDecrement()
                FileUtils.deleteQuietly(it)
            }.onFailure { e -> Logging.e("FileDeletionHandler", "Failed to delete ${it.absolutePath}", e) }
        }
        currentTask?.let { task -> if (task.isCancelled) return }
        //剩下的都是空文件夹，直接删除
        mSelectedFiles.forEach {
            runCatching { FileUtils.deleteQuietly(it) }
                .onFailure { e -> Logging.e("FileDeletionHandler", "Failed to delete root ${it.absolutePath}", e) }
        }
    }

    override fun getCurrentFileCount() = fileCount.get()

    override fun getTotalSize() = totalFileSize.get()

    override fun getPendingSize() = fileSize.get()

    override fun onEnd() {
        endTask?.execute()
    }
}