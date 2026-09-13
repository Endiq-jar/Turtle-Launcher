package com.endiq.turtlelauncher.utils.platform

import android.content.Context
import android.os.PowerManager
import com.endiq.turtlelauncher.feature.log.Logging

/**
 * TurtleLauncher battery-aware performance profiles.
 *
 * Rather than reading raw battery percentage and guessing a threshold, this reads
 * whether Android's own Battery Saver is currently on (PowerManager.isPowerSaveMode,
 * API 21+) - the OS-level signal the user (or the OS's automatic low-battery
 * trigger) has already decided the device should conserve power right now.
 * [AutoSettingsOptimizer] treats this the same way it treats thermal throttling:
 * cap the FPS-boost profile instead of maximizing frame rate on a device that's
 * explicitly trying to save power.
 */
object BatterySaverManager {
    private const val TAG = "BatterySaverManager"

    fun isPowerSaveMode(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isPowerSaveMode ?: false
        } catch (e: Exception) {
            Logging.e(TAG, "Failed to read power save mode: $e")
            false
        }
    }
}
