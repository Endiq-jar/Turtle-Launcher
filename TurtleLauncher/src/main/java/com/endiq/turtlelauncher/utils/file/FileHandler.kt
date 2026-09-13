package com.endiq.turtlelauncher.utils.file

import android.app.Activity
import android.content.Context
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.ProgressDialog
import com.endiq.turtlelauncher.utils.ZHTools
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Future

abstract class FileHandler(
    protected val context: Context,
) {
    protected var currentTask: Future<*>? = null
    private var timer: Timer? = null
    private var lastSize: Long = 0
    private var lastTime: Long = ZHTools.getCurrentTimeMillis()

    //上下文背后的Activity是否仍然有效（没有finish/destroy）
    //避免在Activity已经销毁后，对Dialog进行show/dismiss操作导致崩溃
    private fun isContextAlive(): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    //安全地显示/隐藏弹窗，防止 BadTokenException / IllegalArgumentException 等窗口异常使App崩溃
    private fun safeDialogAction(action: () -> Unit) {
        if (!isContextAlive()) return
        runCatching { action() }
            .onFailure { e -> Logging.e("FileHandler", "Dialog action failed", e) }
    }

    protected fun start(progress: FileSearchProgress) {
        TaskExecutors.runInUIThread {
            if (!isContextAlive()) {
                //Activity已经不存在了，直接结束，不展示弹窗
                onEnd()
                return@runInUIThread
            }

            val dialog = ProgressDialog(context) {
                cancelTask()
                onEnd()
                true
            }
            dialog.updateText(context.getString(R.string.file_operation_file, "0 B", "0 B", 0))

            currentTask = TaskExecutors.getDefault().submit {
                //整个后台任务都用try-catch包裹：任何单点异常都不应该变成未捕获异常杀掉整个App进程
                try {
                    TaskExecutors.runInUIThread { safeDialogAction { dialog.show() } }

                    timer = Timer()
                    timer?.schedule(object : TimerTask() {
                        override fun run() {
                            val pendingSize = progress.getPendingSize()
                            val totalSize = progress.getTotalSize()
                            val processedSize = totalSize - pendingSize

                            val currentTime = ZHTools.getCurrentTimeMillis()
                            val timeElapsed = (currentTime - lastTime) / 1000.0
                            val sizeChange = processedSize - lastSize
                            val rate = (if (timeElapsed > 0) sizeChange / timeElapsed else 0.0).toLong()

                            lastSize = processedSize
                            lastTime = currentTime

                            TaskExecutors.runInUIThread {
                                safeDialogAction {
                                    dialog.updateText(
                                        context.getString(
                                            R.string.file_operation_file,
                                            FileTools.formatFileSize(pendingSize),
                                            FileTools.formatFileSize(totalSize),
                                            progress.getCurrentFileCount()
                                        )
                                    )
                                    dialog.updateRate(rate)
                                    dialog.updateProgress(
                                        processedSize.toDouble(),
                                        totalSize.toDouble()
                                    )
                                }
                            }
                        }
                    }, 0, 100)

                    searchFilesToProcess()
                    currentTask?.let { task -> if (task.isCancelled) return@submit }
                    processFile()
                } catch (e: Throwable) {
                    Logging.e("FileHandler", "File operation failed unexpectedly", e)
                } finally {
                    runCatching { timer?.cancel() }
                    TaskExecutors.runInUIThread { safeDialogAction { dialog.dismiss() } }
                    runCatching { onEnd() }
                        .onFailure { e -> Logging.e("FileHandler", "onEnd failed", e) }
                }
            }
        }
    }

    abstract fun searchFilesToProcess()

    abstract fun processFile()

    abstract fun onEnd()

    private fun cancelTask() {
        currentTask?.let {
            if (!it.isDone) {
                it.cancel(true)
                timer?.cancel()
            }
        }
    }
}