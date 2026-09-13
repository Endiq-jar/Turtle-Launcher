package com.endiq.turtlelauncher.context

import android.content.Context
import android.content.res.Configuration
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * TurtleLauncher: Accessibility (roadmap item 22) - larger UI scaling, high-contrast mode, and
 * a font family override.
 *
 * Two independent mechanisms, both applied from BaseActivity:
 *  - wrapContext(): wraps the base Context with a Configuration whose fontScale reflects
 *    AllSettings.fontScale (a percentage, 50-200, default 100 - a setting that already existed
 *    with no UI or consumer before this). Deliberately only touches fontScale, not densityDpi -
 *    scaling density as well would also resize icons/touch targets/layout dimensions app-wide,
 *    a much bigger and riskier change than what "larger UI scaling" for readability needs.
 *    fontScale alone affects sp-based text sizes only, the same mechanism Android's own
 *    Settings > Display > Font size uses.
 *  - applyHighContrastOverlay()/applyFontFamilyOverride(): applied to the Activity's theme /
 *    default font family in onCreate(), see BaseActivity.
 *
 * Note: none of this reaches Minecraft's own in-game GUI, which is rendered natively and has
 * no dependency on Android Resources/Configuration - see AccessibilitySettingsFragment's doc
 * comment for the full explanation of that boundary.
 */
object AccessibilityHelper {
    @JvmStatic
    fun wrapContext(context: Context): Context {
        val percent = AllSettings.fontScale.getValue().coerceIn(50, 200)
        if (percent == 100) return context

        val configuration = Configuration(context.resources.configuration)
        configuration.fontScale = percent / 100f
        return context.createConfigurationContext(configuration)
    }

    @JvmStatic
    fun applyHighContrastOverlay(context: Context) {
        if (AllSettings.highContrastMode.getValue()) {
            context.theme.applyStyle(R.style.ThemeOverlay_Turtle_HighContrast, true)
        }
    }

    @JvmStatic
    fun applyFontFamilyOverride(context: Context) {
        val styleRes = when (AllSettings.fontFamily.getValue()) {
            "sans-serif" -> R.style.ThemeOverlay_Turtle_Font_Sans
            "sans-serif-condensed" -> R.style.ThemeOverlay_Turtle_Font_Condensed
            "serif" -> R.style.ThemeOverlay_Turtle_Font_Serif
            "monospace" -> R.style.ThemeOverlay_Turtle_Font_Monospace
            else -> null // "default" (or unset) - keep AppTheme's own branding font, nothing to do
        }
        styleRes?.let { context.theme.applyStyle(it, true) }
    }
}
