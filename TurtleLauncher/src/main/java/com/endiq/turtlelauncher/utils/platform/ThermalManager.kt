package com.endiq.turtlelauncher.utils.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.endiq.turtlelauncher.feature.log.Logging

/**
 * TurtleLauncher thermal awareness.
 *
 * Android exposes the device's actual thermal state via PowerManager since API 29
 * (Build.VERSION_CODES.Q) - this is the OS telling you it is *already* about to
 * throttle clocks, rather than something inferred indirectly after the fact from
 * dropped frames. [AutoSettingsOptimizer] reads this before picking a performance
 * profile so it doesn't hand an already-warm device a profile (RAM/FPS boost
 * flags/resolution) that immediately drives it into aggressive OS throttling,
 * which would erase the profile's own benefit within minutes of play.
 */
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
