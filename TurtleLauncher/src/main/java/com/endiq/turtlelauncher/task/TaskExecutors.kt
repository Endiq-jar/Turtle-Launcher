package com.endiq.turtlelauncher.task

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TaskExecutors {
    companion object {
        // Size the pool from the device core count and let idle threads time out:
        // Low-end devices are not pinned to 4 threads: the pool grows to the core count under
        // load and shrinks again when idle, saving battery and memory.
        private val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        private val corePoolSize = cpuCores.coerceIn(2, 4)
        private val maxPoolSize = (cpuCores).coerceAtLeast(corePoolSize)

        // TurtleLauncher: set true right before Minecraft's own Activity starts (a real game
        // session, not just "the launcher isn't visible" - a paused/backgrounded launcher still
        // legitimately runs downloads at normal priority, e.g. via ProgressService), cleared once
        // back at the launcher. beforeExecute() is a standard ThreadPoolExecutor extension point -
        // this only lowers the OS scheduling priority of tasks in this shared pool so the game
        // process wins CPU contention, it never pauses/cancels/drops any queued or in-flight work.
        @Volatile
        @JvmStatic
        var isGameSessionActive: Boolean = false

        private val defaultExecutors: ExecutorService = object : ThreadPoolExecutor(
            corePoolSize, maxPoolSize, 10_000, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
        ) {
            override fun beforeExecute(t: Thread, r: Runnable) {
                super.beforeExecute(t, r)
                android.os.Process.setThreadPriority(
                    if (isGameSessionActive) android.os.Process.THREAD_PRIORITY_BACKGROUND
                    else android.os.Process.THREAD_PRIORITY_DEFAULT
                )
            }
        }.apply {
            // Let core threads be reclaimed after idling instead of living forever.
            allowCoreThreadTimeOut(true)
        }
        private val uiHandler = Handler(Looper.getMainLooper())

        @JvmStatic
        fun getDefault(): ExecutorService {
            return defaultExecutors
        }

        @JvmStatic
        fun getAndroidUI(): Executor {
            return Executor { r: Runnable -> uiHandler.post(r) }
        }

        @JvmStatic
        fun getUIHandler() = uiHandler

        @JvmStatic
        fun runInUIThread(runnable: Runnable) {
            uiHandler.post(runnable)
        }
    }
}