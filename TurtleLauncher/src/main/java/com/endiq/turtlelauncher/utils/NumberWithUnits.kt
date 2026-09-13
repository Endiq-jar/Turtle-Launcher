package com.endiq.turtlelauncher.utils

import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.context.ContextExecutor.Companion.getString
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat


class NumberWithUnits {
    companion object {
        private val UNITS_EN = arrayOf("", "K", "M") // English units: thousand, million
        private val UNITS_ZH = arrayOf(
            "",
            getString(R.string.generic_wan),
            getString(R.string.generic_yi)
        ) // Chinese-style units (ten thousand / hundred million)

        @JvmStatic
        fun formatNumberWithUnit(number: Long, isEnglish: Boolean): String {
            return if (isEnglish) {
                formatNumberWithUnitEnglish(number)
            } else {
                formatNumberWithUnitChinese(number)
            }
        }

        private fun formatNumberWithUnitChinese(number: Long): String {
            return formatNumber(number, 10000, UNITS_ZH)
        }

        private fun formatNumberWithUnitEnglish(number: Long): String {
            return formatNumber(number, 1000, UNITS_EN)
        }

        private fun formatNumber(number: Long, stage: Int, units: Array<String>): String {
            var bigDecimal = BigDecimal(number)
            var unitIndex = 0

            while (bigDecimal >= BigDecimal.valueOf(stage.toLong()) && unitIndex < units.size - 1) {
                bigDecimal = bigDecimal.divide(BigDecimal.valueOf(stage.toLong()), 2, RoundingMode.DOWN)
                unitIndex++
            }

            // If the unit is empty, skip formatting and return the raw value.
            if (units[unitIndex].isEmpty()) {
                return number.toString()
            } else {
                val df = DecimalFormat("#.00")
                val formattedNumber = df.format(bigDecimal.setScale(2, RoundingMode.DOWN).toDouble())
                return StringUtils.insertSpace(formattedNumber, units[unitIndex])
            }
        }
    }
}
