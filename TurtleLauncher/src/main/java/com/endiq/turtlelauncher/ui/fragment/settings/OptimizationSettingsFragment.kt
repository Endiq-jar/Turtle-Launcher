package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentOptimizationBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools


class OptimizationSettingsFragment : AbstractSettingsFragment(R.layout.settings_fragment_optimization, SettingCategory.OPTIMIZATION) {
    companion object {
        const val TAG: String = "OptimizationSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentOptimizationBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentOptimizationBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        SwitchSettingsWrapper(context, AllSettings.unlimitedFps,
            binding.unlimitedFpsLayout, binding.unlimitedFps)

        SwitchSettingsWrapper(context, AllSettings.lowLatencyRendering,
            binding.lowLatencyRenderingLayout, binding.lowLatencyRendering)

        SwitchSettingsWrapper(context, AllSettings.framePacing,
            binding.framePacingLayout, binding.framePacing)

        SwitchSettingsWrapper(context, AllSettings.frameSkipping,
            binding.frameSkippingLayout, binding.frameSkipping)

        SwitchSettingsWrapper(context, AllSettings.adaptiveFrameTiming,
            binding.adaptiveFrameTimingLayout, binding.adaptiveFrameTiming)

        SwitchSettingsWrapper(context, AllSettings.autoMemoryCleanup,
            binding.autoMemoryCleanupLayout, binding.autoMemoryCleanup)

        SwitchSettingsWrapper(context, AllSettings.rendererShaderCacheEnabled,
            binding.rendererShaderCacheLayout, binding.rendererShaderCache)

        SwitchSettingsWrapper(context, AllSettings.rendererDebugLogging,
            binding.rendererDebugLoggingLayout, binding.rendererDebugLogging)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }
}
