package com.endiq.turtlelauncher.setting.unit

import com.endiq.turtlelauncher.setting.Settings.Manager

class StringSettingUnit(key: String, defaultValue: String) : AbstractSettingUnit<String>(key, defaultValue) {
    override fun getValue() = Manager.getValue(key, defaultValue) { it }
}