package com.endiq.turtlelauncher.utils.stringutils

import java.util.Locale
import java.util.regex.Pattern

class StringFilter {
    companion object {
        @JvmStatic
        fun containsSubstring(input: String, substring: String, caseSensitive: Boolean): Boolean {
            val adjustedInput = if (caseSensitive) input else input.lowercase(Locale.getDefault())
            val adjustedSubstring =
                if (caseSensitive) substring else substring.lowercase(Locale.getDefault())
            val regex = Pattern.quote(adjustedSubstring)
            val compiledPattern = Pattern.compile(regex)
            val matcher = compiledPattern.matcher(adjustedInput)
            return matcher.find()
        }
    }
}
