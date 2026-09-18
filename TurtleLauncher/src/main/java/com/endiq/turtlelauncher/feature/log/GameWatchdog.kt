package com.endiq.turtlelauncher.feature.log

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the live game log file while Minecraft is running inside
 * JavaGUILauncherActivity. Some failures don't make the JVM exit at all — the
 * render thread can simply hang (black screen, frozen window) while the process
 * stays alive — so [JvmExitEvent]/exit-code based crash detection never fires.
 *
 * This watchdog polls latestlog.txt's size on a timer; if it stops growing for
 * [STALL_TIMEOUT_MS] it shows a best-effort diagnosis (reusing [CrashAnalyzer])
 * with an option to force-close, instead of leaving the person staring at a dead
 * screen with no feedback at all.
 *
 * Deliberately does NOT use [net.endiq.launcher.Logger.setLogListener]: that is a
 * single global native callback slot already used by the in-game log viewer
 * (com.endiq.LoggerView), and attaching a second consumer there would silently steal
 * or lose callbacks depending on which one last called setLogListener. Polling the
 * log file's length avoids that conflict entirely.
 */
class GameWatchdog(
    private val activity: Activity,
    private val onForceClose: Runnable
) {
    private val handler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val dialogShowing = AtomicBoolean(false)

    private var lastSeenLength = -1L
    private var lastChangeAt = System.currentTimeMillis()

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (stopped.get()) return

            val logFile = File(PathManager.DIR_GAME_HOME, "latestlog.txt")
            val length = if (logFile.exists()) logFile.length() else -1L
            val now = System.currentTimeMillis()

            if (length != lastSeenLength) {
                lastSeenLength = length
                lastChangeAt = now
            }

            val idleFor = now - lastChangeAt
            if (idleFor >= STALL_TIMEOUT_MS && dialogShowing.compareAndSet(false, true)) {
                showStallDialog(logFile)
            }

            if (!stopped.get()) handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** Starts watching. Safe to call once per game session. */
    fun start() {
        stopped.set(false)
        lastSeenLength = -1L
        lastChangeAt = System.currentTimeMillis()
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, INITIAL_DELAY_MS)
    }

    /** Stops watching. Must be called when the game activity is destroyed/finished. */
    fun stop() {
        stopped.set(true)
        handler.removeCallbacks(checkRunnable)
    }

    private fun showStallDialog(logFile: File) {
        if (activity.isFinishing || activity.isDestroyed) return

        val diagnosis = CrashAnalyzer.analyzeFrozenState(CrashAnalyzer.tailOf(logFile, 32 * 1024))
        val message = CrashAnalyzer.formatForDisplay(listOf(diagnosis))

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                dialogShowing.set(false)
                return@runOnUiThread
            }
            TipDialog.Builder(activity)
                .setTitle(R.string.watchdog_frozen_title)
                .setMessage(message)
                .setWarning()
                .setCenterMessage(false)
                .setSelectable(true)
                .setCancelable(true)
                .setConfirm(R.string.watchdog_force_close)
                .setCancel(R.string.watchdog_keep_waiting)
                .setConfirmClickListener { _ -> onForceClose.run() }
                .setCancelClickListener {
                    // Give it another full timeout window before warning again.
                    dialogShowing.set(false)
                    lastChangeAt = System.currentTimeMillis()
                }
                .showDialog()
        }
    }

    companion object {
        private const val INITIAL_DELAY_MS = 20_000L
        private const val CHECK_INTERVAL_MS = 6_000L
        private const val STALL_TIMEOUT_MS = 30_000L
    }
}
