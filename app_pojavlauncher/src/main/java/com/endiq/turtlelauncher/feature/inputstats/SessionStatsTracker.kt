package com.endiq.turtlelauncher.feature.inputstats

import com.endiq.turtlelauncher.feature.turtle.DailyPlaytimeStats
import com.endiq.turtlelauncher.setting.AllSettings

object SessionStatsTracker {
    @Volatile private var sessionStartMs: Long = 0L
    @Volatile private var running: Boolean = false

    /** Call once when the game actually starts rendering. */
    @JvmStatic
    fun start() {
        sessionStartMs = System.currentTimeMillis()
        running = true
    }

    /** Call when the game session ends; flushes this session's time into the persisted total. */
    @JvmStatic
    fun stop() {
        if (running) {
            val elapsed = getSessionElapsedMs()
            AllSettings.totalPlaytimeMs.put(AllSettings.totalPlaytimeMs.getValue() + elapsed).save()
            DailyPlaytimeStats.recordSession(elapsed)
        }
        running = false
        sessionStartMs = 0L
    }

    /** Elapsed time (ms) since this session started; 0 if not currently in a session. */
    @JvmStatic
    fun getSessionElapsedMs(): Long {
        if (!running || sessionStartMs == 0L) return 0L
        return System.currentTimeMillis() - sessionStartMs
    }

    /** Cumulative playtime (ms) across all sessions, including the current one in progress. */
    @JvmStatic
    fun getTotalPlaytimeMs(): Long = AllSettings.totalPlaytimeMs.getValue() + getSessionElapsedMs()

    @JvmStatic
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
