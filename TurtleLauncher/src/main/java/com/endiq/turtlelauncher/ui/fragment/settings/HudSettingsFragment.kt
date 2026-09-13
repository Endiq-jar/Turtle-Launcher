package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentHudBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.EditTextSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools


/**
 * TurtleLauncher: the in-game menu overlay settings (previously "setting_category_game_menu")
 * and the in-game HUD module toggles (previously "setting_category_hud_modules") were both
 * really about the same thing - what's drawn on top of the game while playing - just under two
 * different names, both mixed into GameSettingsFragment alongside language/version/presets.
 * Split out into their own page here, same reasoning as JavaSettingsFragment.
 */
class HudSettingsFragment : AbstractSettingsFragment(R.layout.settings_fragment_hud, SettingCategory.HUD) {
    companion object {
        const val TAG: String = "HudSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentHudBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentHudBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        SwitchSettingsWrapper(
            context,
            AllSettings.gameMenuShowMemory,
            binding.gameMenuShowMemoryLayout,
            binding.gameMenuShowMemory
        ).setOnCheckedChangeListener { _, _, listener ->
            listener.onSave()
            openGameMenuMemory()
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.gameMenuShowNativeMemory,
            binding.gameMenuShowNativeMemoryLayout,
            binding.gameMenuShowNativeMemory
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gameMenuShowFPS,
            binding.gameMenuShowFPSLayout,
            binding.gameMenuShowFPS
        ).setOnCheckedChangeListener { _, _, listener ->
            listener.onSave()
            openGameMenuFPS()
        }

        EditTextSettingsWrapper(
            AllSettings.gameMenuMemoryText,
            binding.gameMenuMemoryTextLayout,
            binding.gameMenuMemoryText
        ).setOnTextChangedListener {
            updateGameMenuMemoryText()
        }.setMaxLength(40)

        ListSettingsWrapper(
            context,
            AllSettings.gameMenuLocation,
            binding.gameMenuLocationLayout,
            binding.gameMenuLocationTitle,
            binding.gameMenuLocationValue,
            R.array.game_menu_location_names, R.array.game_menu_location_values
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.gameMenuInfoRefreshRate,
            binding.gameMenuInfoRefreshRateLayout,
            binding.gameMenuInfoRefreshRateTitle,
            binding.gameMenuInfoRefreshRateSummary,
            binding.gameMenuInfoRefreshRateValue,
            binding.gameMenuInfoRefreshRate,
            "ms"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.gameMenuAlpha,
            binding.gameMenuAlphaLayout,
            binding.gameMenuAlphaTitle,
            binding.gameMenuAlphaSummary,
            binding.gameMenuAlphaValue,
            binding.gameMenuAlpha,
            "%"
        ).setOnSeekBarProgressChangeListener { progress ->
            setGameMenuAlpha(progress.toFloat() / 100F)
        }

        openGameMenuMemory()
        openGameMenuFPS()
        updateGameMenuMemoryText()
        setGameMenuAlpha(AllSettings.gameMenuAlpha.getValue().toFloat() / 100F)

        SwitchSettingsWrapper(context, AllSettings.showCpsHud, binding.showCpsHudLayout, binding.showCpsHud)
        SwitchSettingsWrapper(context, AllSettings.showKeystrokesHud, binding.showKeystrokesHudLayout, binding.showKeystrokesHud)
        SwitchSettingsWrapper(context, AllSettings.showMousestrokesHud, binding.showMousestrokesHudLayout, binding.showMousestrokesHud)
        SwitchSettingsWrapper(context, AllSettings.showStopwatchHud, binding.showStopwatchHudLayout, binding.showStopwatchHud)
        SwitchSettingsWrapper(context, AllSettings.showPlaytimeHud, binding.showPlaytimeHudLayout, binding.showPlaytimeHud)
        SwitchSettingsWrapper(context, AllSettings.showSystemResourcesHud, binding.showSystemResourcesHudLayout, binding.showSystemResourcesHud)
        SwitchSettingsWrapper(context, AllSettings.showTimeHud, binding.showTimeHudLayout, binding.showTimeHud)
        SwitchSettingsWrapper(context, AllSettings.showRamGraphHud, binding.showRamGraphHudLayout, binding.showRamGraphHud)
        SwitchSettingsWrapper(context, AllSettings.showPingHud, binding.showPingHudLayout, binding.showPingHud)
        SwitchSettingsWrapper(context, AllSettings.showScreenshotButtonHud, binding.showScreenshotButtonHudLayout, binding.showScreenshotButtonHud)
        // showRecordButtonHud lives only in RecordingSettingsFragment now - see that file.
        // Independent HUD Dragging removed outright (was here) - see GameMenuViewWrapper.kt
        SeekBarSettingsWrapper(
            context,
            AllSettings.hudModuleScale,
            binding.hudModuleScaleLayout,
            binding.hudModuleScaleTitle,
            binding.hudModuleScaleSummary,
            binding.hudModuleScaleValue,
            binding.hudModuleScale,
            suffix = "%"
        )

        // PvP overlay preset: one toggle that flips CPS + Keystrokes + Mousestrokes together.
        // It mirrors its own state from those three on load (checked only if all three are
        // already on) and, when tapped, pushes the new state down to all three switches.
        binding.pvpOverlayPreset.isChecked = AllSettings.showCpsHud.getValue() &&
            AllSettings.showKeystrokesHud.getValue() && AllSettings.showMousestrokesHud.getValue()
        binding.pvpOverlayPresetLayout.setOnClickListener {
            binding.pvpOverlayPreset.isChecked = !binding.pvpOverlayPreset.isChecked
        }
        binding.pvpOverlayPreset.setOnCheckedChangeListener { _, isChecked ->
            AllSettings.pvpOverlayPreset.put(isChecked).save()
            AllSettings.showCpsHud.put(isChecked).save()
            AllSettings.showKeystrokesHud.put(isChecked).save()
            AllSettings.showMousestrokesHud.put(isChecked).save()
            binding.showCpsHud.isChecked = isChecked
            binding.showKeystrokesHud.isChecked = isChecked
            binding.showMousestrokesHud.isChecked = isChecked
        }

        SeekBarSettingsWrapper(
            context,
            AllSettings.hudAlpha,
            binding.hudAlphaLayout,
            binding.hudAlphaTitle,
            binding.hudAlphaSummary,
            binding.hudAlphaValue,
            binding.hudAlpha,
            "%"
        )
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    private fun openGameMenuMemory() {
        binding.gameMenuPreview.memoryText.visibility = if (AllSettings.gameMenuShowMemory.getValue()) View.VISIBLE else View.GONE
    }

    private fun openGameMenuFPS() {
        binding.gameMenuPreview.fpsText.visibility = if (AllSettings.gameMenuShowFPS.getValue()) View.VISIBLE else View.GONE
    }

    private fun setGameMenuAlpha(alpha: Float) {
        binding.gameMenuPreview.root.alpha = alpha
    }

    private fun updateGameMenuMemoryText() {
        val text = "${AllSettings.gameMenuMemoryText.getValue()} 0MB/0MB"
        binding.gameMenuPreview.memoryText.text = text.trim()
    }
}
