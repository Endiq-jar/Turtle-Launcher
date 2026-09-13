package com.endiq.turtlelauncher.setting.unit

import com.endiq.turtlelauncher.setting.Settings.Manager

class FloatSettingUnit(key: String, defaultValue: Float) : AbstractSettingUnit<Float>(key, defaultValue) {
    override fun getValue() = Manager.getValue(key, defaultValue) { it.toFloatOrNull() }
}