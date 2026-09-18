package com.endiq.turtlelauncher.setting

import androidx.annotation.CheckResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.endiq.turtlelauncher.event.single.SettingsChangeEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.unit.AbstractSettingUnit
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap


class Settings {
    companion object {
        private val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

        private val settingsLock = Any()
        private var settingsMap = ConcurrentHashMap<String, SettingAttribute>()

        private fun refreshSettingsMap(): Map<String, SettingAttribute> {
            return PathManager.FILE_SETTINGS.takeIf { it.exists() }?.let { file ->
                try {
                    val jsonString = Tools.read(file)
                    val listType: Type = object : TypeToken<List<SettingAttribute>>() {}.type
                    GSON.fromJson<List<SettingAttribute>>(jsonString, listType)
                        .associateBy { it.key }
                } catch (e: Exception) {
                    Logging.e("Settings", "Failed to refresh settings: ${Tools.printToString(e)}")
                    emptyMap()
                }
            } ?: emptyMap()
        }

        /**
         * Refresh every launcher setting.
         */
        @Synchronized
        fun refreshSettings() {
            settingsMap = ConcurrentHashMap(refreshSettingsMap())
        }
    }

    class Manager private constructor() {
        companion object {
            /**
             * Read the value of a key from the launcher settings.
             */
            fun <T> getValue(key: String, defaultValue: T, parser: (String) -> T?): T {
                // A corrupt settings file must never crash the app: an unparseable
                // value falls back to the default instead of throwing out of every
                // getValue() call site.
                return settingsMap[key]?.value?.let { runCatching { parser(it) }.getOrNull() } ?: defaultValue
            }

            /**
             * Check whether a key exists in the launcher settings.
             */
            @JvmStatic
            fun contains(key: String): Boolean {
                return settingsMap.containsKey(key)
            }

            /**
             * Store a key/value pair in the launcher settings.
             */
            @JvmStatic
            @CheckResult
            fun put(key: String, value: Any) = SettingBuilder().put(key, value)
        }

        class SettingBuilder {
            private val valueMap = ConcurrentHashMap<String, Any>()

            /**
             * Store a key/value pair in the launcher settings.
             */
            @CheckResult
            fun put(key: String, value: Any): SettingBuilder {
                valueMap[key] = value
                return this
            }

            /**
             * Store a key/value pair in the launcher settings.
             * @param unit the setting unit
             */
            @CheckResult
            fun put(unit: AbstractSettingUnit<*>, value: Any): SettingBuilder {
                return put(unit.key, value)
            }

            fun save() {
                val settingsFile = PathManager.FILE_SETTINGS
                val newSettings = ConcurrentHashMap(settingsMap)

                valueMap.forEach { (key, value) ->
                    newSettings[key] = SettingAttribute(key, value.toString())
                }

                synchronized(settingsLock) {
                    runCatching {
                        if (!settingsFile.exists() && !settingsFile.createNewFile()) {
                            throw IllegalStateException("Failed to create settings file")
                        }

                        val settingsList = newSettings.values.toList()
                        val json = GSON.toJson(settingsList)
                        FileUtils.write(settingsFile, json, Charsets.UTF_8)
                        refreshSettings()
                        EventBus.getDefault().post(SettingsChangeEvent())
                    }.onFailure { e ->
                        Logging.e("SettingBuilder", "Save failed!", e)
                    }
                }
            }
        }
    }

    private class SettingAttribute(
        var key: String = "",
        var value: String? = null
    )
}