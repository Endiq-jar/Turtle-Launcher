package com.endiq.turtlelauncher.feature.preset

import android.content.Context
import com.endiq.turtlelauncher.setting.AllSettings
import net.endiq.launcher.Tools

object PresetManager {

    fun loadPreset(context: Context, assetFile: String): PresetConfig? {
        return runCatching {
            val json = context.assets.open("turtle_presets/$assetFile").bufferedReader().readText()
            Tools.GLOBAL_GSON.fromJson(json, PresetConfig::class.java)
        }.getOrNull()
    }

    fun applyPreset(context: Context, assetFile: String): Boolean {
        val preset = loadPreset(context, assetFile) ?: return false
        if (preset.javaArgs.isNotEmpty()) {
            AllSettings.javaArgs.put(preset.javaArgs).save()
        }
        if (preset.ramAllocation > 0) {
            AllSettings.ramAllocation.value.put(preset.ramAllocation).save()
        }
        AllSettings.unlimitedFps.put(preset.unlimitedFps).save()
        AllSettings.lowLatencyRendering.put(preset.lowLatencyRendering).save()
        AllSettings.framePacing.put(preset.framePacing).save()
        AllSettings.frameSkipping.put(preset.frameSkipping).save()
        AllSettings.adaptiveFrameTiming.put(preset.adaptiveFrameTiming).save()
        return true
    }

    fun applyPvP(context: Context) = applyPreset(context, "pvp.json")
    fun applySurvival(context: Context) = applyPreset(context, "survival.json")
}
