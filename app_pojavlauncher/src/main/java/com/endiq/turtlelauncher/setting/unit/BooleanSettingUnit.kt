package com.endiq.turtlelauncher.setting.unit

import com.endiq.turtlelauncher.setting.Settings.Manager

class BooleanSettingUnit(key: String, defaultValue: Boolean) : AbstractSettingUnit<Boolean>(key, defaultValue) {
    override fun getValue() = Manager.getValue(key, defaultValue) { it.toBoolean() }
}