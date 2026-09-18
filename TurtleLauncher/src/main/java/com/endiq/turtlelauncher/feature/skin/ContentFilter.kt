package com.endiq.turtlelauncher.feature.skin

internal object ContentFilter {

    private val BLOCKED_SUBSTRINGS = listOf(
        "nigger", "nigga", "faggot", "retard",
        "rape", "nazi", "hitler", "kkk",
        "cp", "loli", "shota", "porn", "hentai", "nude", "naked", "sex"
    )

    fun isBlockedName(rawName: String): Boolean {
        val lower = rawName.lowercase()
        return BLOCKED_SUBSTRINGS.any { blocked ->
            if (blocked == "cp") Regex("\\bcp\\b").containsMatchIn(lower)
            else lower.contains(blocked)
        }
    }

    /** True if [text] contains any codepoint outside Latin script, digits, and common
     *  punctuation/symbols - i.e. would render as something other than English/Latin text. */
    private fun hasNonLatinScript(text: String): Boolean =
        text.codePoints().anyMatch { cp ->
            !(cp in 0x0000..0x024F || // Basic Latin + Latin-1 Supplement + Latin Extended-A/B
              cp in 0x2000..0x206F || // general punctuation
              Character.isDigit(cp))
        }

    fun toDisplayLabel(rawName: String, fallbackId: String): String =
        if (rawName.isBlank() || hasNonLatinScript(rawName)) "Skin #$fallbackId" else rawName
}
