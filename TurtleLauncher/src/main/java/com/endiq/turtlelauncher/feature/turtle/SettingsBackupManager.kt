package com.endiq.turtlelauncher.feature.turtle

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.Settings
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File

/**
 * TurtleLauncher v10: settings backup/restore. The launcher already stores every
 * AllSettings.* value as one JSON array in [PathManager.FILE_SETTINGS], so backup
 * is just a validated copy of that file, and restore is writing a validated backup
 * back into place followed by [Settings.refreshSettings] so every already-loaded
 * setting unit picks up the new values immediately (no relaunch required).
 */
object SettingsBackupManager {
    private const val TAG = "SettingsBackupManager"

    /** Returns the current settings file's content, or null if it doesn't exist / isn't valid JSON. */
    @JvmStatic
    fun exportToJson(): String? {
        return runCatching {
            val file = PathManager.FILE_SETTINGS
            if (!file.exists()) return null
            val text = Tools.read(file)
            // Sanity check it's actually a JSON array before handing it out.
            com.google.gson.JsonParser.parseString(text).asJsonArray
            text
        }.onFailure { e -> Logging.e(TAG, "Settings export failed", e) }.getOrNull()
    }

    @JvmStatic
    fun exportToFile(destFile: File): Boolean {
        val json = exportToJson() ?: return false
        return runCatching {
            destFile.parentFile?.mkdirs()
            destFile.writeText(json)
            true
        }.onFailure { e -> Logging.e(TAG, "Settings export write failed", e) }.getOrDefault(false)
    }

    @JvmStatic
    fun importFromJson(json: String): Boolean {
        return runCatching {
            val parsed = com.google.gson.JsonParser.parseString(json).asJsonArray
            if (parsed.size() > 0 && !(parsed[0].isJsonObject && parsed[0].asJsonObject.has("key"))) {
                return false
            }
            PathManager.FILE_SETTINGS.parentFile?.mkdirs()
            PathManager.FILE_SETTINGS.writeText(json)
            Settings.refreshSettings()
            true
        }.onFailure { e -> Logging.e(TAG, "Settings import failed", e) }.getOrDefault(false)
    }

    @JvmStatic
    fun importFromFile(srcFile: File): Boolean {
        if (!srcFile.exists()) return false
        return runCatching { importFromJson(srcFile.readText()) }.getOrDefault(false)
    }
}
