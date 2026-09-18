package com.endiq.turtlelauncher.utils.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.endiq.turtlelauncher.feature.log.Logging

object ThermalManager {
    private const val TAG = "ThermalManager"

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Current thermal status as a PowerManager.THERMAL_STATUS_* constant.
     * Returns THERMAL_STATUS_NONE (0) on API < 29, where this isn't observable at all.
     */
    fun getCurrentThermalStatus(context: Context): Int {
        if (!isSupported()) return 0 // PowerManager.THERMAL_STATUS_NONE, unavailable pre-API 29
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus ?: 0
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to read thermal status: $e")
            0
        }
    }

    /**
     * True from THERMAL_STATUS_MODERATE (2) upward - the point where Android itself
     * starts throttling CPU/GPU clocks, not merely warning about temperature.
     */
    fun isThrottled(context: Context): Boolean =
        getCurrentThermalStatus(context) >= PowerManager.THERMAL_STATUS_MODERATE

    /**
     * True from THERMAL_STATUS_SEVERE (3) upward - aggressive throttling where even
     * a mid-tier performance profile will feel worse than a conservative one.
     */
    fun isSeverelyThrottled(context: Context): Boolean =
        getCurrentThermalStatus(context) >= PowerManager.THERMAL_STATUS_SEVERE
}
