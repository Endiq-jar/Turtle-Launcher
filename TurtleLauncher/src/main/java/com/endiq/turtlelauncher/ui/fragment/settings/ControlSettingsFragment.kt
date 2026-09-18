package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentControlBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools
import fr.spse.gamepad_remapper.Remapper
import net.endiq.launcher.fragments.GamepadMapperFragment


class ControlSettingsFragment() : AbstractSettingsFragment(R.layout.settings_fragment_control, SettingCategory.CONTROL) {
    companion object {
        const val TAG: String = "ControlSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentControlBinding
    private var parentFragment: FragmentWithAnim? = null

    constructor(parentFragment: FragmentWithAnim?) : this() {
        this.parentFragment = parentFragment
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentControlBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { com.endiq.turtlelauncher.utils.ZHTools.onBackPressed(requireActivity()) }
        // TurtleLauncher: the touch-gesture rows (disable gestures / double tap / long-press
        // delay) and all mouse rows moved to the new Mouse & Keyboard screen
        // (MouseKeyboardSettingsFragment), ported from Zalith Launcher 2's grouping. This
        // screen keeps button appearance, gyro and gamepad settings.

        SeekBarSettingsWrapper(
            context,
            AllSettings.buttonScale,
            binding.buttonscaleLayout,
            binding.buttonscaleTitle,
            binding.buttonscaleSummary,
            binding.buttonscaleValue,
            binding.buttonscale,
            "%"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.buttonAllCaps,
            binding.buttonAllCapsLayout,
            binding.buttonAllCaps
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.enableGyro,
            binding.enableGyroLayout,
            binding.enableGyro
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.gyroSensitivity,
            binding.gyroSensitivityLayout,
            binding.gyroSensitivityTitle,
            binding.gyroSensitivitySummary,
            binding.gyroSensitivityValue,
            binding.gyroSensitivity,
            "%"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.gyroSampleRate,
            binding.gyroSampleRateLayout,
            binding.gyroSampleRateTitle,
            binding.gyroSampleRateSummary,
            binding.gyroSampleRateValue,
            binding.gyroSampleRate,
            "ms"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gyroSmoothing,
            binding.gyroSmoothingLayout,
            binding.gyroSmoothing
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gyroInvertX,
            binding.gyroInvertXLayout,
            binding.gyroInvertX
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gyroInvertY,
            binding.gyroInvertYLayout,
            binding.gyroInvertY
        )

        BaseSettingsWrapper(
            context,
            binding.changeControllerBindingsLayout
        ) {
            ZHTools.swapFragmentWithAnim(
                    this,
                    GamepadMapperFragment::class.java,
                    GamepadMapperFragment.TAG,
                    null
                )
        }

        BaseSettingsWrapper(
            context,
            binding.resetControllerBindingsLayout
        ) {
            Remapper.wipePreferences(context)
            Toast.makeText(context, R.string.setting_controller_map_wiped, Toast.LENGTH_SHORT)
                .show()
        }

        SeekBarSettingsWrapper(
            context,
            AllSettings.deadZoneScale,
            binding.gamepadDeadzoneScaleLayout,
            binding.gamepadDeadzoneScaleTitle,
            binding.gamepadDeadzoneScaleSummary,
            binding.gamepadDeadzoneScaleValue,
            binding.gamepadDeadzoneScale,
            "%"
        )

        val mGyroAvailable =
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            ) != null
        binding.enableGyroCategory.visibility = if (mGyroAvailable) View.VISIBLE else View.GONE

        computeVisibility()
    }

    override fun onChange() {
        super.onChange()
        computeVisibility()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    private fun computeVisibility() {
        binding.apply {
            setViewVisibility(gyroSensitivityLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroSampleRateLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroInvertXLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroInvertYLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroSmoothingLayout, AllSettings.enableGyro.getValue())
        }
    }

    private fun setViewVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}