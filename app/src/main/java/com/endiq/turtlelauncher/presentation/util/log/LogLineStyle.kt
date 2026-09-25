package com.endiq.turtlelauncher.presentation.util.log

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Minimal, deterministic log highlighting shared by the crash viewer and assistant.
 *
 * Normal lines deliberately receive no span style. This keeps long logs cheap to render
 * and avoids turning harmless INFO/DEBUG output into a wall of highlighted text.
 */
enum class LogSeverity { NORMAL, WARNING, ERROR }

object LogLineStyle {
    private val errorPattern = Regex(
        "(^|\\s)(E/|F/|ERROR|FATAL|SEVERE|EXCEPTION|CRASH|CRASHED)(\\b|:)",
        RegexOption.IGNORE_CASE,
    )
    private val warningPattern = Regex(
        "(^|\\s)(W/|WARN|WARNING)(\\b|:)",
        RegexOption.IGNORE_CASE,
    )

    fun severity(line: String): LogSeverity = when {
        errorPattern.containsMatchIn(line) -> LogSeverity.ERROR
        warningPattern.containsMatchIn(line) -> LogSeverity.WARNING
        else -> LogSeverity.NORMAL
    }

    /** Preserve line breaks while styling only error and warning lines. */
    fun styled(text: String): AnnotatedString = buildAnnotatedString {
        text.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            when (severity(line)) {
                LogSeverity.ERROR -> withStyle(SpanStyle(color = Color(0xFFFF7777))) { append(line) }
                LogSeverity.WARNING -> withStyle(SpanStyle(color = Color(0xFFFFC857))) { append(line) }
                LogSeverity.NORMAL -> append(line)
            }
        }
    }
}
