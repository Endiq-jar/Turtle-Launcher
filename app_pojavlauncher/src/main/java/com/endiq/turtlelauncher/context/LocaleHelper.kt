package com.endiq.turtlelauncher.context

import android.content.Context
import android.content.ContextWrapper
import com.endiq.turtlelauncher.setting.Settings
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.prefs.LauncherPreferences

class LocaleHelper(context: Context) : ContextWrapper(context) {
    companion object {
        fun setLocale(context: Context): ContextWrapper {
            // Initialise the paths.
            PathManager.initContextConstants(context)
            // Refresh the launcher settings.
            Settings.refreshSettings()

            LauncherPreferences.loadPreferences(context)
            return LocaleHelper(context)
        }
    }
}