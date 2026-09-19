package com.endiq.turtlelauncher.setting.unit

import androidx.annotation.CheckResult
import com.endiq.turtlelauncher.setting.Settings

abstract class AbstractSettingUnit<V>(
    val key: String,
    val defaultValue: V
) {
    /**
     * @return the current setting value
     */
    abstract fun getValue(): V

    /**
     * @return the stored value plus a settings builder
     */
    @CheckResult
    fun put(value: V): Settings.Manager.SettingBuilder = Settings.Manager.put(key, value!!)

    /**
     * Reset this setting unit back to its default.
     */
    fun reset() {
        Settings.Manager.put(key, defaultValue!!).save()
    }
}