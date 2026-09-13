package com.endiq.turtlelauncher.feature.turtle

import android.os.Handler
import android.os.Looper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * TurtleLauncher v10: lightweight ANR detector. Minecraft on Android can enter a
 * "frozen but not crashed" state (GameWatchdog already catches this for log
 * stagnation), but the *launcher's own UI thread* can also hang independently —
 * e.g. inside a synchronous file operation on the main thread. This posts a
 * heartbeat to the main Looper every tick and, from a background thread, checks
 * that the heartbeat is actually being processed within [AllSettings.anrTimeoutMs].
 * If it isn't, the main thread is blocked — logged with a full thread dump so the
 * cause is identifiable after the fact, without needing to reproduce it live.
 */
object AnrWatchdog {
    private const val TAG = "AnrWatchdog"
    private const val TICK_MS = 1000L

    private val lastHeartbeatAt = AtomicLong(System.currentTimeMillis())
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var reportedForThisHang = false

    @JvmStatic
    fun start() {
        if (running || !AllSettings.anrDetectorEnabled.getValue()) return
        running = true

        thread(name = "TurtleAnrWatchdog", isDaemon = true) {
            while (running) {
                val postedAt = System.currentTimeMillis()
                mainHandler.post { lastHeartbeatAt.set(System.currentTimeMillis()) }

                try {
                    Thread.sleep(TICK_MS)
                } catch (e: InterruptedException) {
                    break
                }

                val timeout = AllSettings.anrTimeoutMs.getValue().toLong()
                val staleFor = System.currentTimeMillis() - lastHeartbeatAt.get()
                if (lastHeartbeatAt.get() < postedAt && staleFor >= timeout) {
                    if (!reportedForThisHang) {
                        reportedForThisHang = true
                        reportHang(staleFor)
                    }
                } else {
                    reportedForThisHang = false
                }
            }
        }
    }

    @JvmStatic
    fun stop() {
        running = false
    }

    private fun reportHang(staleForMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val trace = mainThread.stackTrace.joinToString("\n") { "    at $it" }
        Logging.e(TAG, "Main thread appears unresponsive for ${staleForMs}ms:\n$trace")
    }
}
