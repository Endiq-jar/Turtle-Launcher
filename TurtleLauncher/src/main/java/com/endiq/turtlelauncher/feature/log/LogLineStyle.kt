package com.endiq.turtlelauncher.feature.log

import android.content.Context
import android.text.SpannableString
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.endiq.turtlelauncher.R
import java.util.Locale

/**
 * Applies lightweight, readable severity highlighting to the live and saved
 * game logs. Normal lines deliberately receive no span, so large logs stay
 * cheap to render on low-memory devices.
 */
object LogLineStyle {
    private enum class Severity { ERROR, WARNING, NORMAL }

    @JvmStatic
    fun isError(line: String): Boolean = severity(line) == Severity.ERROR

    @JvmStatic
    fun isWarning(line: String): Boolean = severity(line) == Severity.WARNING

    @JvmStatic
    fun style(context: Context, line: String): CharSequence {
        val styled = SpannableString(line)
        apply(context, styled, 0, styled.length, line)
        return styled
    }

    @JvmStatic
    fun apply(
        context: Context,
        target: Spannable,
        start: Int,
        end: Int,
        line: String
    ) {
        when (severity(line)) {
            Severity.ERROR -> {
                target.setSpan(
                    BackgroundColorSpan(ContextCompat.getColor(context, R.color.log_error_background)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                target.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.log_error_text)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Severity.WARNING -> {
                target.setSpan(
                    BackgroundColorSpan(ContextCompat.getColor(context, R.color.log_warning_background)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                target.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.log_warning_text)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Severity.NORMAL -> Unit
        }
    }

    private fun severity(line: String): Severity {
        val upper = line.uppercase(Locale.ROOT)
        if (
            upper.contains("(ERROR)") ||
            upper.contains("[ERROR]") ||
            upper.contains("ERROR:") ||
            upper.contains(" E/") ||
            upper.contains("FATAL") ||
            upper.contains("EXCEPTION") ||
            upper.contains("SIGSEGV") ||
            upper.contains("CRASH") ||
            upper.contains("FAILED")
        ) {
            return Severity.ERROR
        }
        if (
            upper.contains("(WARN)") ||
            upper.contains("[WARN]") ||
            upper.contains("WARNING") ||
            upper.contains("WARN:") ||
            upper.contains(" W/")
        ) {
            return Severity.WARNING
        }
        return Severity.NORMAL
    }
}
