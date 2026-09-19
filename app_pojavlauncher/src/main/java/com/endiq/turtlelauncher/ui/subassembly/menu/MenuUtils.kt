package com.endiq.turtlelauncher.ui.subassembly.menu

import android.annotation.SuppressLint
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MenuUtils {
    companion object {
        /**
         * Adjust the seekbar value.
         * @param seekBar the seek bar
         * @param v how far the value should be adjusted
         */
        @JvmStatic
        fun adjustSeekbar(seekBar: SeekBar, v: Int) {
            seekBar.progress += v
        }

        /**
         * Invert the current checked state of the switch.
         */
        @JvmStatic
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        fun toggleSwitchState(switchView: Switch) {
            switchView.isChecked = !switchView.isChecked
        }

        /**
         * Initialise the seekbar value.
         */
        @JvmStatic
        fun initSeekBarValue(seek: SeekBar, value: Int, valueView: TextView, suffix: String) {
            seek.progress = value
            updateSeekbarValue(value, valueView, suffix)
        }

        /**
         * Update the value text next to the seekbar.
         */
        @JvmStatic
        fun updateSeekbarValue(value: Int, valueView: TextView, suffix: String) {
            val valueText = "$value $suffix"
            valueView.text = valueText.trim { it <= ' ' }
        }
    }
}