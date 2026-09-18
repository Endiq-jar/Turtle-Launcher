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

object NativeCrashCapture {
    private const val TAG = "NativeCrashCapture"
    private const val PREFS_NAME = "native_crash_capture"
    private const val KEY_LAST_REPORTED_TIMESTAMP = "last_reported_timestamp"
    private const val MAX_LOG_CHARS = 64 * 1024
    private const val MAX_CRASH_REPORT_CHARS = 48 * 1024
    private const val CRASH_REPORT_MATCH_WINDOW_MS = 3 * 60 * 1000L

    private const val CRASH_FILE_NAME = "latestlog.txt"
    private const val MAX_CRASH_FILE_CHARS = 256 * 1024

    @JvmStatic
    fun checkAndReport(context: Context) {
        // ApplicationExitInfo doesn't exist before API 30. Below that, this is simply a no-op -
        // there is no equivalent facility on older Android to fall back to without root.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

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
                prefs.edit().putLong(KEY_LAST_REPORTED_TIMESTAMP, newest.timestamp).apply()
                return
            }

            val traceText = runCatching {
                newest.traceInputStream?.use { it.bufferedReader().readText() }
            }.getOrNull()

            val logTail = runCatching {
                File(PathManager.DIR_GAME_HOME, "latestlog.txt").takeIf { it.isFile }
                    ?.readText()?.takeLast(MAX_LOG_CHARS)
            }.getOrNull().orEmpty().let {
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

            ErrorActivity.showExitMessage(context, newest.status, true, diagnosisText)
        }.onFailure { Logging.e(TAG, "checkAndReport failed", it) }
    }

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
