package com.endiq.turtlelauncher.feature.turtle

import com.endiq.turtlelauncher.setting.AllSettings
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DailyPlaytimeStats {
    private const val RETAIN_DAYS = 14L
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Call once a game session ends, with how long it lasted - adds to today's bucket. */
    @JvmStatic
    @Synchronized
    fun recordSession(elapsedMs: Long) {
        if (elapsedMs <= 0) return
        val today = LocalDate.now()
        val json = readJson()
        val key = today.format(DAY_FORMAT)
        json.put(key, json.optLong(key, 0L) + elapsedMs)
        writeJson(prune(json, today))
    }

    /** Milliseconds played today. */
    @JvmStatic
    fun getTodayMs(): Long {
        val key = LocalDate.now().format(DAY_FORMAT)
        return readJson().optLong(key, 0L)
    }

    /**
     * The last 7 calendar days' totals, oldest first, always Monday..Sunday of the current
     * week (matches a standard weekly-chart layout regardless of which day "today" is).
     */
    @JvmStatic
    fun getThisWeekMs(): LongArray {
        val json = readJson()
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        return LongArray(7) { i -> json.optLong(monday.plusDays(i.toLong()).format(DAY_FORMAT), 0L) }
    }

    private fun prune(json: JSONObject, today: LocalDate): JSONObject {
        val cutoff = today.minusDays(RETAIN_DAYS)
        val result = JSONObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val date = runCatching { LocalDate.parse(key, DAY_FORMAT) }.getOrNull() ?: continue
            if (!date.isBefore(cutoff)) {
                result.put(key, json.optLong(key, 0L))
            }
        }
        return result
    }

    private fun readJson(): JSONObject =
        runCatching { JSONObject(AllSettings.dailyPlaytimeJson.getValue()) }.getOrDefault(JSONObject())

    private fun writeJson(json: JSONObject) {
        AllSettings.dailyPlaytimeJson.put(json.toString()).save()
    }
}
