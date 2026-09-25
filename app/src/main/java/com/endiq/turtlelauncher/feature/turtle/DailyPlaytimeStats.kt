package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Original Turtle's 14-day playtime buckets, backed by app-private storage. */
object DailyPlaytimeStats {
    private const val PREFS = "turtle_playtime"
    private const val KEY_DAYS = "days"
    private const val RETAIN_DAYS = 14L
    private val format = DateTimeFormatter.ISO_LOCAL_DATE

    @Synchronized
    fun recordSession(context: Context, elapsedMs: Long) {
        if (elapsedMs <= 0) return
        val today = LocalDate.now()
        val json = read(context)
        val key = today.format(format)
        json.put(key, json.optLong(key, 0L) + elapsedMs)
        val cutoff = today.minusDays(RETAIN_DAYS)
        val pruned = JSONObject()
        json.keys().forEach { dateKey ->
            val date = runCatching { LocalDate.parse(dateKey, format) }.getOrNull()
            if (date != null && !date.isBefore(cutoff)) pruned.put(dateKey, json.optLong(dateKey))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DAYS, pruned.toString()).apply()
    }

    fun todayMs(context: Context): Long =
        read(context).optLong(LocalDate.now().format(format), 0L)

    /** Monday through Sunday of the current calendar week, matching the old chart. */
    fun thisWeekMs(context: Context): LongArray {
        val json = read(context)
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        return LongArray(7) { index -> json.optLong(monday.plusDays(index.toLong()).format(format), 0L) }
    }

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DAYS, "{}"))
    }.getOrDefault(JSONObject())
}
