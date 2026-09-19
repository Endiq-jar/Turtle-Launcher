package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.SettingsFragmentGameBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.EditTextSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper


class GameSettingsFragment : AbstractSettingsFragment(R.layout.settings_fragment_game, SettingCategory.GAME) {
    companion object {
        const val TAG: String = "GameSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentGameBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentGameBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { com.endiq.turtlelauncher.utils.ZHTools.onBackPressed(requireActivity()) }

        // Quick Presets (PvP/Survival) removed per explicit request - was here.

        SwitchSettingsWrapper(
            context,
            AllSettings.versionIsolation,
            binding.versionIsolationLayout,
            binding.versionIsolation
        )

        EditTextSettingsWrapper(
            AllSettings.versionCustomInfo,
            binding.versionCustomInfoLayout,
            binding.versionCustomInfoEdittext
        )

        EditTextSettingsWrapper(
            AllSettings.curseForgeApiKey,
            binding.curseForgeApiKeyLayout,
            binding.curseForgeApiKeyEdittext
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.autoSetGameLanguage,
            binding.autoSetGameLanguageLayout,
            binding.autoSetGameLanguage
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gameLanguageOverridden,
            binding.gameLanguageOverriddenLayout,
            binding.gameLanguageOverridden
        )

        ListSettingsWrapper(
            context,
            AllSettings.setGameLanguage,
            binding.setGameLanguageLayout,
            binding.setGameLanguageTitle,
            binding.setGameLanguageValue,
            R.array.all_game_language, R.array.all_game_language_value
        )

    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }
}