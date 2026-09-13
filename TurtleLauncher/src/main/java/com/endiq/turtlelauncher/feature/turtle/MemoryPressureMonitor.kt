package com.endiq.turtlelauncher.feature.turtle

import android.app.ActivityManager
import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.platform.MemoryUtils.Companion.getTotalDeviceMemory
import kotlin.concurrent.thread

/**
 * TurtleLauncher Phone Settings: Memory Pressure Monitor. Android will kill the launcher's
 * (and, since the JVM runs in-process, the game's) process outright once the system decides
 * it's under memory pressure - usually with no warning the player can act on. This polls
 * ActivityManager.MemoryInfo periodically and logs a warning as soon as either the system
 * reports `lowMemory`, or available memory drops under a fraction of total device RAM, so a
 * crash can be traced back to "the OS was starving us" after the fact instead of looking like
 * an ordinary crash in CrashAnalyzer's history.
 *
 * Distinct from AllSettings.autoMemoryCleanup (a periodic idle-time G1 GC nudge for the *game*
 * JVM heap) - this watches *device-wide* memory, independent of whether a game is running.
 */
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
