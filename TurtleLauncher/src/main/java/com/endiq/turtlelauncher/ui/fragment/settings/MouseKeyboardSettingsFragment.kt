package com.endiq.turtlelauncher.ui.fragment.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.endiq.anim.AnimPlayer
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentMouseKeyboardBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.CustomMouseFragment
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

/**
 * Settings -> Mouse & Keyboard.
 *
 * TurtleLauncher: dedicated home for every mouse/keyboard control, ported from Zalith
 * Launcher 2's control settings (ZalithLauncher2 ControlSettingsScreen.kt + AllSettings):
 * physical mouse mode, mouse capture sensitivity, hide-cursor, configurable tap /
 * long-press gesture mouse buttons and a bindable physical key that opens the on-screen
 * keyboard - merged with this project's existing virtual-mouse and touch-gesture rows
 * relocated here from the Controls screen (which keeps buttons, gyro and gamepad).
 * Every row is wired to real runtime behaviour: AndroidPointerCapture,
 * InGameEventProcessor, LeftClickGesture/RightClickGesture, Touchpad and
 * MinecraftGLSurface.processKeyEvent.
 */
class MouseKeyboardSettingsFragment() : AbstractSettingsFragment(R.layout.settings_fragment_mouse_keyboard, SettingCategory.MOUSE_KEYBOARD) {
    companion object {
        const val TAG: String = "MouseKeyboardSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentMouseKeyboardBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentMouseKeyboardBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        // ── Mouse ─────────────────────────────────────────────────────────────
        SwitchSettingsWrapper(
            context,
            AllSettings.physicalMouseMode,
            binding.physicalMouseModeLayout,
            binding.physicalMouseMode
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.mouseCaptureSensitivity,
            binding.mouseCaptureSensitivityLayout,
            binding.mouseCaptureSensitivityTitle,
            binding.mouseCaptureSensitivitySummary,
            binding.mouseCaptureSensitivityValue,
            binding.mouseCaptureSensitivity,
            "%"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.hideMouse,
            binding.hideMouseLayout,
            binding.hideMouse
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.mouseScale,
            binding.mousescaleLayout,
            binding.mousescaleTitle,
            binding.mousescaleSummary,
            binding.mousescaleValue,
            binding.mousescale,
            "%"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.mouseSpeed,
            binding.mousespeedLayout,
            binding.mousespeedTitle,
            binding.mousespeedSummary,
            binding.mousespeedValue,
            binding.mousespeed,
            "%"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.virtualMouseStart,
            binding.mouseStartLayout,
            binding.mouseStart
        )

        BaseSettingsWrapper(
            context,
            binding.customMouseLayout
        ) {
            ZHTools.swapFragmentWithAnim(
                this,
                CustomMouseFragment::class.java,
                CustomMouseFragment.TAG,
                null
            )
        }

        // ── Touch gestures ────────────────────────────────────────────────────
        SwitchSettingsWrapper(
            context,
            AllSettings.disableGestures,
            binding.disableGesturesLayout,
            binding.disableGestures
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.disableDoubleTap,
            binding.disableDoubleTapLayout,
            binding.disableDoubleTap
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.timeLongPressTrigger,
            binding.timeLongPressTriggerLayout,
            binding.timeLongPressTriggerTitle,
            binding.timeLongPressTriggerSummary,
            binding.timeLongPressTriggerValue,
            binding.timeLongPressTrigger,
            "ms"
        )

        // Zalith2 gestureTapMouseAction / gestureLongPressMouseAction, wired through
        // GestureButtons into RightClickGesture (tap) and LeftClickGesture (long press).
        val buttonEntries = arrayOf(
            getString(R.string.mouse_button_left),
            getString(R.string.mouse_button_right)
        )
        val buttonValues = arrayOf("left", "right")

        ListSettingsWrapper(
            context,
            AllSettings.gestureTapMouseAction,
            binding.gestureTapActionLayout,
            binding.gestureTapActionTitle,
            binding.gestureTapActionValue,
            buttonEntries,
            buttonValues
        )

        ListSettingsWrapper(
            context,
            AllSettings.gestureLongPressMouseAction,
            binding.gestureLongPressActionLayout,
            binding.gestureLongPressActionTitle,
            binding.gestureLongPressActionValue,
            buttonEntries,
            buttonValues
        )

        // ── Keyboard ──────────────────────────────────────────────────────────
        updateImeKeyLabel()
        BaseSettingsWrapper(
            context,
            binding.physicalKeyImeLayout
        ) {
            showImeKeyBindDialog()
        }

        computeVisibility()
    }

    /** Zalith2's PhysicalKeyImeTrigger flow, adapted to this project's dialog-based
     *  settings UI: tap the row, then the next physical key pressed becomes the binding.
     *  Back cancels; Unbind clears the binding. */
    private fun showImeKeyBindDialog() {
        val context = context ?: return
        val builder = AlertDialog.Builder(context, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.setting_physical_key_ime_title)
            .setMessage(R.string.setting_physical_key_ime_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)

        val bound = AllSettings.physicalKeyImeCode.getValue()
        if (bound != -1) {
            builder.setNeutralButton(R.string.setting_physical_key_ime_unbind) { dialog, _ ->
                AllSettings.physicalKeyImeCode.put(-1).save()
                updateImeKeyLabel()
                dialog.dismiss()
            }
        }

        val dialog = builder.create()
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                AllSettings.physicalKeyImeCode.put(keyCode).save()
                updateImeKeyLabel()
                dialog.dismiss()
                true
            } else {
                // Let the dialog keep handling BACK (cancel) and key-up events.
                false
            }
        }
        dialog.show()
    }

    private fun updateImeKeyLabel() {
        val code = AllSettings.physicalKeyImeCode.getValue()
        binding.physicalKeyImeValue.text = if (code == -1) {
            getString(R.string.setting_physical_key_ime_unbound)
        } else {
            getString(R.string.setting_physical_key_ime_bound, KeyEvent.keyCodeToString(code))
        }
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
            // The gesture-button/delay rows only mean something while gestures are on -
            // same gating the Controls screen used for the long-press delay row.
            val gesturesEnabled = !AllSettings.disableGestures.getValue()
            setViewVisibility(timeLongPressTriggerLayout, gesturesEnabled)
            setViewVisibility(gestureTapActionLayout, gesturesEnabled)
            setViewVisibility(gestureLongPressActionLayout, gesturesEnabled)
        }
    }

    private fun setViewVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
