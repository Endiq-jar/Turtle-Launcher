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

    // Whether the Activity behind the context is still alive (not finished/destroyed).
    // Avoid crashing by showing/dismissing a dialog after the Activity was destroyed.
    private fun isContextAlive(): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    // Show/dismiss dialogs safely so window exceptions (BadTokenException,
    // IllegalArgumentException, ...) cannot crash the app.
    private fun safeDialogAction(action: () -> Unit) {
        if (!isContextAlive()) return
        runCatching { action() }
            .onFailure { e -> Logging.e("FileHandler", "Dialog action failed", e) }
    }

    protected fun start(progress: FileSearchProgress) {
        TaskExecutors.runInUIThread {
            if (!isContextAlive()) {
                // The Activity is gone: bail out without showing a dialog.
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
                // Wrap the whole background task in try-catch: a single failure must never
				// become an uncaught exception that kills the app process.
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