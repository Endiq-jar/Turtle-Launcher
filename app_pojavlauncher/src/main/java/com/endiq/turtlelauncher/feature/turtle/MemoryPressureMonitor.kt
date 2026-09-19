package com.endiq.turtlelauncher.feature.turtle

import android.app.ActivityManager
import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.platform.MemoryUtils.Companion.getTotalDeviceMemory
import kotlin.concurrent.thread

object MemoryPressureMonitor {
    private const val TAG = "MemoryPressureMonitor"
    private const val TICK_MS = 5000L
    // Warn once available memory drops under 15% of total device RAM, even if the system
    // hasn't flagged lowMemory itself yet (OEM thresholds for that vary a lot).
    private const val LOW_MEMORY_RATIO = 0.15

    @Volatile private var running = false
    @Volatile private var lastWarnedAt = 0L
    private const val WARN_COOLDOWN_MS = 30_000L

    @JvmStatic
    fun start(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: run {
                Logging.e(TAG, "ActivityManager unavailable, monitor not starting")
                running = false
                return
            }

        thread(name = "TurtleMemoryPressureMonitor", isDaemon = true) {
            val totalMem = getTotalDeviceMemory(appContext)
            while (running) {
                val info = ActivityManager.MemoryInfo()
                runCatching { activityManager.getMemoryInfo(info) }

                val ratio = if (totalMem > 0) info.availMem.toDouble() / totalMem else 1.0
                val underPressure = info.lowMemory || ratio < LOW_MEMORY_RATIO

                if (underPressure) {
                    val now = System.currentTimeMillis()
                    if (now - lastWarnedAt >= WARN_COOLDOWN_MS) {
                        lastWarnedAt = now
                        Logging.w(
                            TAG,
                            "Device under memory pressure: available=${info.availMem / (1024 * 1024)}MB " +
                                "(${(ratio * 100).toInt()}% of ${totalMem / (1024 * 1024)}MB total), " +
                                "system lowMemory=${info.lowMemory}, threshold=${info.threshold / (1024 * 1024)}MB"
                        )
                    }
                }

                try {
                    Thread.sleep(TICK_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    @JvmStatic
    fun stop() {
        running = false
    }
}
