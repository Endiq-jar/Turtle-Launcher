package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentAccessibilityBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools


class AccessibilitySettingsFragment : AbstractSettingsFragment(R.layout.settings_fragment_accessibility, SettingCategory.ACCESSIBILITY) {
    companion object {
        const val TAG: String = "AccessibilitySettingsFragment"
    }

    private lateinit var binding: SettingsFragmentAccessibilityBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentAccessibilityBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        SwitchSettingsWrapper(context, AllSettings.highContrastMode, binding.highContrastModeLayout, binding.highContrastMode)
            .setRequiresReboot()

        SeekBarSettingsWrapper(
            context,
            AllSettings.fontScale,
            binding.fontScaleLayout,
            binding.fontScaleTitle,
            binding.fontScaleSummary,
            binding.fontScaleValue,
            binding.fontScale,
            "%"
        ).setRequiresReboot()

        ListSettingsWrapper(
            context,
            AllSettings.fontFamily,
            binding.fontFamilyLayout,
            binding.fontFamilyTitle,
            binding.fontFamilyValue,
            R.array.all_font_family, R.array.all_font_family_value
        ).setRequiresReboot()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }
}
