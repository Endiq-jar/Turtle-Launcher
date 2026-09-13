package com.endiq.turtlelauncher.feature.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.activity.ErrorActivity
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * TurtleLauncher Native Crash Capture.
 *
 * Root problem this exists for: MC 26.3+'s SDL3 SIGSEGV (and any other native-level crash -
 * a bad renderer .so, a bad JNI call, etc) kills the whole OS process instantly via a signal.
 * Since VMLauncher.launchJVM() runs the guest JVM IN-PROCESS (JNI_CreateJavaVM, same process
 * as the launcher itself, not a separate exec'd process), a signal like this doesn't just end
 * "the game" - it kills the launcher too, with no chance for ANY Java code to run: no
 * uncaught-exception handler (that's Java-level only, TurtleApplication's own handler in
 * onCreate() can't see this), no JREUtils.launchJavaVM()'s post-launchJVM() exit-code handling
 * (that line of code is simply never reached - the process is already dead), no
 * CrashAnalyzer.analyzeGameExit(). This is exactly why "no crash logs" was a real, structural
 * gap and not a logging bug to patch around - there was no code path left alive to do any
 * logging at the moment of the crash.
 *
 * The fix: Android itself keeps a short history of *why* a process died, independent of
 * whether that process is still alive to report it, via ActivityManager.
 * getHistoricalProcessExitReasons() (API 30+). For a native crash it can even hand back the
 * actual native trace (tombstone-style backtrace) via ApplicationExitInfo.getTraceInputStream().
 * Nothing here needs the native source this launcher doesn't have - it's reading a record the
 * OS already kept, on the *next* cold start, since obviously nothing can run at the instant of
 * the kill itself.
 *
 * Call [checkAndReport] once, early in app startup, after PathManager.DIR_DATA/DIR_LAUNCHER_LOG
 * are resolvable (see TurtleStartupInitializer, which already documents that exact ordering
 * constraint for the same reason).
 *
 * IMPORTANT: this only tells us the *launcher process* died via a signal - it says nothing
 * about whether Minecraft itself had already hit a real, diagnosable game-side problem (a
 * bad mod, a corrupted resource, an OOM) moments before something native finished the job.
 * So on top of the OS-level trace, this also checks for a Minecraft-written crash-report
 * file (crash-reports folder's .txt files, wherever this install's game dir/version isolation puts it)
 * with a timestamp close enough to the process death to plausibly be the same event, and
 * runs it through the *same* CrashAnalyzer rule engine analyzeGameExit() already uses for
 * the graceful-exit path - so a launch that ends this way gets the real "your mod X is
 * incompatible" style diagnosis when Minecraft managed to write one, not just a raw native
 * backtrace. Shown via ErrorActivity.showExitMessage (the game-crash screen), not
 * showLauncherCrash (the launcher-crash screen) - this failure mode lives in the
 * game/renderer layer, not launcher code, and should read that way to the user.
 */
object NativeCrashCapture {
    private const val TAG = "NativeCrashCapture"
    private const val PREFS_NAME = "native_crash_capture"
    private const val KEY_LAST_REPORTED_TIMESTAMP = "last_reported_timestamp"
    private const val MAX_LOG_CHARS = 64 * 1024
    private const val MAX_CRASH_REPORT_CHARS = 48 * 1024
    /** How close a crash-reports folder's .txt file's mtime must be to the recorded process-death
     *  timestamp to be trusted as "this is the report for THIS death" rather than some
     *  unrelated older crash still sitting on disk. */
    private const val CRASH_REPORT_MATCH_WINDOW_MS = 3 * 60 * 1000L

    /** Same file TurtleApplication's Java-uncaught-exception handler uses. They used to be
     *  kept apart specifically to avoid one overwriting the other - see git history - but
     *  Endiq asked for every crash type to land under one name. To keep that safe, both
     *  writers now PREPEND their new report (newest report first, older ones kept below a
     *  separator) instead of truncating the file, capped at [MAX_CRASH_FILE_CHARS] total. */
    private const val CRASH_FILE_NAME = "latestlog.txt"
    private const val MAX_CRASH_FILE_CHARS = 256 * 1024

    @JvmStatic
    fun checkAndReport(context: Context) {
        // ApplicationExitInfo doesn't exist before API 30. Below that, this is simply a no-op -
        // there is no equivalent facility on older Android to fall back to without root.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        // TurtleLauncher: everything below is disk I/O (multiple crash-reports folders, up to
        // MAX_LOG_CHARS/MAX_CRASH_REPORT_CHARS of file reads, a native trace stream) plus
        // CrashAnalyzer.analyze()'s ~31 rules run over that combined text - real work, not
        // a quick check. This used to run inline on whatever thread called checkAndReport(),
        // which is TurtleStartupInitializer.create() on the main thread during
        // TurtleApplication.onCreate() - confirmed via an AnrWatchdog "main thread unresponsive
        // for 5003ms" report with this exact call chain in the stack. Every step here is
        // read-only against on-disk state from the *previous* run and ErrorActivity.showExitMessage
        // only calls startActivity (fine off the main thread), so there's nothing here that
        // needs to happen before TurtleApplication.onCreate() continues - moved onto the shared
        // background pool instead of blocking startup.
        TaskExecutors.getDefault().execute { checkAndReportBlocking(context) }
    }

    private fun checkAndReportBlocking(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            // null package = this app's own package; maxRecords 5 is plenty, we only ever
            // care about the single newest one.
            val reasons = am.getHistoricalProcessExitReasons(null, 0, 5)
            if (reasons.isEmpty()) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastReported = prefs.getLong(KEY_LAST_REPORTED_TIMESTAMP, 0L)

            val newest = reasons.maxByOrNull { it.timestamp } ?: return
            // Already reported this exact exit on a previous cold start - don't show it again
            // every single time the app opens.
            if (newest.timestamp <= lastReported) return

            val isRelevant = newest.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                newest.reason == ApplicationExitInfo.REASON_ANR
            if (!isRelevant) {
                // A normal exit (user swiped away, REASON_USER_REQUESTED, etc) - not a crash,
                // nothing to report, but still worth remembering so an old boring exit doesn't
                // get compared against forever. Only advance the marker on exits we recognize,
                // so a reason we don't have a name for yet doesn't get silently swallowed if a
                // future Android version adds one worth surfacing.
                prefs.edit().putLong(KEY_LAST_REPORTED_TIMESTAMP, newest.timestamp).apply()
                return
            }

            val traceText = runCatching {
                newest.traceInputStream?.use { it.bufferedReader().readText() }
            }.getOrNull()

            // The launcher's own raw stdout/stderr capture - whatever Minecraft printed right
            // up to the moment it died. Same file analyzeGameExit() reads for the graceful-exit
            // path, so a launch that ends via a signal instead gets the same source consulted.
            val logTail = runCatching {
                File(PathManager.DIR_GAME_HOME, "latestlog.txt").takeIf { it.isFile }
                    ?.readText()?.takeLast(MAX_LOG_CHARS)
            }.getOrNull().orEmpty().let {
                // TurtleLauncher: a signal-killed process takes the native logger's buffer
                // with it, so this is empty in exactly the case this class exists for. Fall
                // back to the logcat drain from that session (kept in logd, so it survives),
                // then to a fresh one-shot dump, so the report is never blank.
                if (it.isNotBlank()) it
                else GameLogcat.readSessionTail(MAX_LOG_CHARS).ifBlank { GameLogcat.dumpNow() }
            }

            val mcCrashFile = findRecentMinecraftCrashReport(newest.timestamp)
            val mcCrashText = mcCrashFile?.let {
                runCatching { it.readText().takeLast(MAX_CRASH_REPORT_CHARS) }.getOrNull()
            }

            // Same rule engine analyzeGameExit() uses for the graceful-exit path - a real
            // "your mod X is incompatible" diagnosis when Minecraft got far enough to write one,
            // not just a raw native backtrace with no explanation attached.
            val diagnoses = runCatching {
                val combinedLog = if (traceText.isNullOrBlank()) logTail else "$logTail\n$traceText"
                CrashAnalyzer.analyze(combinedLog, mcCrashText, null)
            }.getOrDefault(emptyList())
            val diagnosisText = diagnoses.takeIf { it.isNotEmpty() }
                ?.let { runCatching { CrashAnalyzer.formatForDisplay(it, null) }.getOrNull() }

            val reportText = buildString {
                appendLine("TurtleLauncher native crash report")
                appendLine(" - Time: ${DateFormat.getDateTimeInstance().format(Date(newest.timestamp))}")
                appendLine(" - Reason: ${reasonName(newest.reason)} (status ${newest.status})")
                appendLine(" - System description: ${newest.description ?: "<none given>"}")
                appendLine(" - Process importance at death: ${newest.importance}")
                appendLine(" - Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                appendLine()
                if (!mcCrashText.isNullOrBlank()) {
                    appendLine("── Minecraft's own crash report (${mcCrashFile?.name}) ──")
                    append(mcCrashText)
                    appendLine()
                    appendLine()
                }
                if (traceText.isNullOrBlank()) {
                    appendLine("<no native trace was attached to this exit by the system>")
                } else {
                    appendLine("── Native trace ──")
                    append(traceText)
                }
            }

            val crashFile = File(PathManager.DIR_LAUNCHER_LOG, CRASH_FILE_NAME)
            runCatching {
                crashFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
                val previous = if (crashFile.isFile) runCatching { crashFile.readText() }.getOrNull() else null
                val combined = if (previous.isNullOrBlank()) reportText
                    else "$reportText\n\n════════ earlier report(s) below ════════\n\n$previous"
                crashFile.writeText(combined.take(MAX_CRASH_FILE_CHARS))
            }.onFailure { Logging.e(TAG, "Failed to write native crash report", it) }

            prefs.edit().putLong(KEY_LAST_REPORTED_TIMESTAMP, newest.timestamp).apply()

            // showExitMessage, not showLauncherCrash: this reads as a GAME crash (SDL/renderer/
            // native layer, or Minecraft's own reported problem when we found a matching
            // crash-report), using the exact same screen + diagnosis format the graceful-exit
            // path already shows, rather than the separate "the launcher itself crashed" screen.
            ErrorActivity.showExitMessage(context, newest.status, true, diagnosisText)
        }.onFailure { Logging.e(TAG, "checkAndReport failed", it) }
    }

    /**
     * Scans every crash-reports folder this install could plausibly have written to - the
     * shared/default game dir plus every per-version isolated one - without going through
     * VersionsManager (its in-memory version list isn't guaranteed loaded yet this early in
     * startup; this reads the folders directly off disk instead). Returns the single
     * newest file found, but only if its mtime falls within [CRASH_REPORT_MATCH_WINDOW_MS]
     * of [nearTimestamp] - otherwise it's some earlier, unrelated crash still sitting on disk
     * and returning it would misattribute it to this death.
     */
    private fun findRecentMinecraftCrashReport(nearTimestamp: Long): File? {
        val candidates = mutableListOf<File>()

        fun collectFrom(gameDir: File) {
            File(gameDir, "crash-reports")
                .takeIf { it.isDirectory }
                ?.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.let { candidates.addAll(it) }
        }

        // No version isolation / custom path unset - the shared .minecraft/
        collectFrom(File(ProfilePathHome.getGameHome()))
        // Each isolated version's own folder - .minecraft/versions/<name>/
        File(ProfilePathHome.getVersionsHome())
            .takeIf { it.isDirectory }
            ?.listFiles { f -> f.isDirectory }
            ?.forEach { collectFrom(it) }

        val newest = candidates.maxByOrNull { it.lastModified() } ?: return null
        return newest.takeIf { abs(it.lastModified() - nearTimestamp) <= CRASH_REPORT_MATCH_WINDOW_MS }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "Native crash (a signal like SIGSEGV/SIGABRT killed the whole process outside the JVM - e.g. in a renderer .so, SDL, or another native library)"
        ApplicationExitInfo.REASON_ANR -> "App Not Responding (the app froze long enough for the system to kill it)"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        else -> "Other (reason code $reason)"
    }
}
