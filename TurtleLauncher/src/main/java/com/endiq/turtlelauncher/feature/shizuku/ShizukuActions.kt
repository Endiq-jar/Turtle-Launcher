package com.endiq.turtlelauncher.feature.shizuku

import android.os.Build
import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * TurtleLauncher: the concrete things this launcher can do *only* with Shizuku.
 *
 * Everything here is best-effort by design. What an ADB-shell (uid 2000) or root (uid 0)
 * process may do differs per Android version and per ROM, and a command failing is normal -
 * e.g. `renice` to a negative priority needs CAP_SYS_NICE, which the ADB shell user does not
 * have on a user build. None of these failures are errors in this launcher; the UI reports
 * each one honestly rather than claiming success.
 *
 * Every function must be called off the main thread (they block on shell I/O).
 */
object ShizukuActions {
    private const val TAG = "ShizukuActions"

    /** One attempted action and what actually happened, for display in the settings screen. */
    data class ActionOutcome(
        val label: String,
        val ok: Boolean,
        val detail: String = ""
    )

    /** The process name of the running game, as declared in AndroidManifest.xml. */
    private fun gameProcessName() = "${BuildConfig.APPLICATION_ID}:game"

    /**
     * Privileged logcat dump.
     *
     * Unlike GameLogcat (which can only read this app's own lines, since that is all an
     * unprivileged process is allowed to see), this runs as the shell user and so returns the
     * FULL system log - including tombstones, SurfaceFlinger, ActivityManager kill messages
     * and everything else that explains a native crash the game's own log never got to write.
     *
     * @return the log text, or "" when Shizuku is unavailable or the command failed.
     */
    @JvmOverloads
    fun dumpLogcat(maxLines: Int = 4000): String {
        if (!ShizukuManager.isReady) return ""
        val result = ShizukuShell.run("logcat -d -v threadtime -t $maxLines", timeoutSeconds = 20)
        if (!result.ran) return ""
        return result.stdout
    }

    /**
     * Grants the permissions this launcher keeps having to send people to the Settings app
     * for, and takes it out of battery optimization so a long game session is not throttled
     * or killed while it runs in the background.
     */
    fun grantPermissions(): List<ActionOutcome> {
        val packageName = BuildConfig.APPLICATION_ID
        val outcomes = mutableListOf<ActionOutcome>()

        // All-files access. MANAGE_EXTERNAL_STORAGE is not a normal runtime permission - it
        // is granted through AppOps, which is exactly what `adb shell appops set` does. This
        // is the same mechanism the Settings toggle flips, without the user having to hunt
        // for it through Settings > Apps > Special access.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outcomes += attempt("All files access") {
                ShizukuShell.run("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
            }
        }

        // Notification permission: needed on Android 13+ for download/game notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            outcomes += attempt("Notifications") {
                ShizukuShell.run("pm grant $packageName android.permission.POST_NOTIFICATIONS")
            }
        }

        // Legacy storage permissions only mean anything below Android 10; from 10 onwards
        // they are scoped-storage-limited and `pm grant` of them is a no-op at best.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            outcomes += attempt("Storage (legacy)") {
                val read = ShizukuShell.run("pm grant $packageName android.permission.READ_EXTERNAL_STORAGE")
                val write = ShizukuShell.run("pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE")
                if (read.ok && write.ok) read else write
            }
        }

        // Doze/battery-optimization whitelist. Without it Android can (and does) suspend the
        // launcher's background work mid-session, which is one way a game "just closes".
        outcomes += attempt("Battery optimization exemption") {
            ShizukuShell.run("dumpsys deviceidle whitelist +$packageName")
        }

        return outcomes
    }

    /**
     * System-level changes that keep a long, memory-hungry Minecraft session alive and
     * responsive. All of them are the documented `adb shell` workarounds for Android
     * behaviours that hurt this kind of app.
     */
    fun applyPerformanceTweaks(): List<ActionOutcome> {
        val packageName = BuildConfig.APPLICATION_ID
        val outcomes = mutableListOf<ActionOutcome>()

        // Android 14 added a hard cap (32) on the number of "phantom processes" an app may
        // fork. Minecraft on Android is started by forking a JVM out of the app, so on a
        // device that has been running a while this limit is hit and the game is killed with
        // no Java-visible reason at all. This is the single biggest "black screen, then it
        // just closes" cause on Android 14+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            outcomes += attempt("Disable phantom-process monitoring") {
                ShizukuShell.run("settings put global settings_enable_monitor_phantom_procs false")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            outcomes += attempt("Raise phantom-process limit") {
                val global = ShizukuShell.run("settings put global max_phantom_processes 2147483647")
                // device_config is where ActivityManager actually reads the limit from on
                // 12L+; `settings` is kept as the older/other path. Either one succeeding
                // is enough, so report the better of the two.
                val config = ShizukuShell.run("device_config put activity_manager max_phantom_processes 2147483647")
                if (global.ok || config.ok) global else config
            }
        }

        // AOT-compile the launcher's hot paths so cold start and asset work are not running
        // interpreted while the game is also trying to use the CPU. Slow, so generous timeout.
        outcomes += attempt("Compile launcher for speed") {
            ShizukuShell.run("cmd package compile -f -m speed-profile $packageName", timeoutSeconds = 120)
        }

        outcomes += attempt("Raise game process priority") { boostGameProcessPriority() }

        return outcomes
    }

    /**
     * Called from the launch path: applies the keep-alive tweaks automatically, when both
     * this integration and the "apply automatically" setting are on.
     *
     * Threading is deliberate: this does NOT use TaskExecutors' shared pool, because that
     * pool is deprioritised for the whole of a game session (see
     * TaskExecutors.setGameSessionActive) and is already busy with launch work - a shell
     * command queued there could sit behind it for seconds, which is precisely when the
     * phantom-process tweak needs to have landed. A one-shot daemon thread keeps this
     * entirely off the launch path, and nothing waits on its result.
     */
    @JvmStatic
    fun applyBeforeLaunchIfEnabled() {
        // Cheap synchronous checks first: on the overwhelmingly common case (no Shizuku
        // installed) this returns without even creating a thread.
        if (!AllSettings.shizukuEnabled.getValue()) return
        if (!AllSettings.shizukuAutoPerformance.getValue()) return
        if (!ShizukuManager.isReady) return

        Thread({
            runCatching { applyPerformanceTweaks() }
                .onFailure { Logging.w(TAG, "Automatic performance tweaks failed", it) }
        }, "turtle-shizuku-boost").apply { isDaemon = true; start() }
    }

    /**
     * `renice` the running game (and the launcher) so background work and the system's own
     * scheduling are less likely to starve the render thread.
     *
     * Best-effort: lowering a nice value needs CAP_SYS_NICE, which the ADB shell user may not
     * have. Reported, not assumed.
     */
    private fun boostGameProcessPriority(): ShizukuShell.Result {
        val pids = findPidsOf(gameProcessName())
        if (pids.isEmpty()) {
            return ShizukuShell.Result("", "The game process is not running right now", -1, ran = true)
        }
        var last = ShizukuShell.Result("", "no renice attempted", -1, ran = true)
        for (pid in pids) {
            last = ShizukuShell.run("renice -n -10 -p $pid")
        }
        return last
    }

    /**
     * Resolves a process name to its PID(s).
     *
     * `ps` column layouts differ between toybox and toolbox builds, so this parses
     * `PID NAME` first and falls back to a substring match over the whole `ps -A` output.
     */
    private fun findPidsOf(processName: String): List<Int> {
        val structured = ShizukuShell.run("ps -A -o PID,NAME")
        if (structured.ok) {
            val pids = structured.stdout.lineSequence().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == processName) parts[0].toIntOrNull() else null
            }.toList()
            if (pids.isNotEmpty()) return pids
        }

        // Fallback: last whitespace-separated field of `ps -A` is the process name.
        val raw = ShizukuShell.run("ps -A")
        if (!raw.ok) return emptyList()
        return raw.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts.last() == processName) parts[0].toIntOrNull() else null
        }.toList()
    }

    private fun attempt(label: String, block: () -> ShizukuShell.Result): ActionOutcome {
        val result = runCatching { block() }.getOrElse { t ->
            Logging.w(TAG, "Shizuku action failed: $label", t)
            ShizukuShell.Result("", t.message ?: "failed", -1, ran = false)
        }
        return ActionOutcome(label, result.ok, result.summary())
    }
}
