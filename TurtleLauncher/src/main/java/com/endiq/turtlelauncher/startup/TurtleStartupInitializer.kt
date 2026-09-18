package com.endiq.turtlelauncher.startup

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.startup.Initializer
import com.google.android.material.color.DynamicColors
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.log.NativeCrashCapture
import com.endiq.turtlelauncher.feature.turtle.AnrWatchdog

class TurtleStartupInitializer : Initializer<Unit> {
    companion object {
        private const val TAG = "TurtleStartupInitializer"
    }

    override fun create(context: Context) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Material You dynamic color: on supported devices (Android 12+), derives the theme
        // from the system wallpaper; a no-op safe fallback to the static theme elsewhere.
        try {
            DynamicColors.applyToActivitiesIfAvailable(context.applicationContext as Application)
        } catch (t: Throwable) {
            Logging.e(TAG, "Failed to apply dynamic colors", t)
        }

        AnrWatchdog.start()

        NativeCrashCapture.checkAndReport(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
