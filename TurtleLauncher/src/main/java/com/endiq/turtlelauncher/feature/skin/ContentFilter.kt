package com.endiq.turtlelauncher.feature.skin

/**
 * Shared text-only filtering for gallery tile labels ([LittleSkinGalleryApi] and, where a
 * label exists, [LabyModGalleryApi]). Two separate, honest limitations up front:
 *
 * 1. [isBlockedName] is a keyword blocklist against the uploader-given skin/cape *name* text
 *    only. It cannot look at the actual texture pixels - nothing in this project has an image
 *    classifier, and building one is well outside a "fix the gallery" ask. A skin with an
 *    innocuous name and an offensive texture, or vice versa, will not be caught either way.
 *    This only ever removes a tile; it never rewrites or "cleans" one.
 * 2. [toDisplayLabel] does not translate anything - there's no translation API wired into this
 *    project (would need a real key/service, e.g. Google Cloud Translation, none of which is
 *    configured here). What it actually does is detect labels that aren't representable in
 *    Latin script and swap them for a neutral placeholder, the same way [LabyModGalleryApi]
 *    already falls back to a hash prefix when it has no name at all. That keeps every visible
 *    label in English text, but a Chinese/Japanese/Korean/etc. skin name becomes "Skin #123",
 *    not an actual English translation of what the uploader called it.
 */
internal object ContentFilter {

    // Deliberately short and generic rather than exhaustive - this is a basic net against
    // overtly offensive uploaded names, not a moderation system. Case-insensitive substring
    // match on the raw name.
    private val BLOCKED_SUBSTRINGS = listOf(
        "nigger", "nigga", "faggot", "retard",
        "rape", "nazi", "hitler", "kkk",
        "cp", "loli", "shota", "porn", "hentai", "nude", "naked", "sex"
    )

    fun isBlockedName(rawName: String): Boolean {
        val lower = rawName.lowercase()
        return BLOCKED_SUBSTRINGS.any { blocked ->
            // "cp" alone is too short/common a substring (e.g. "cpvp", "capes") to blanket-match -
            // require it as a standalone word instead of any substring, unlike the other terms.
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

    /** Returns [rawName] as-is if it's already representable in Latin script, otherwise a
     *  neutral English placeholder built from [fallbackId] - see class doc for why this is a
     *  fallback, not a translation. */
    fun toDisplayLabel(rawName: String, fallbackId: String): String =
        if (rawName.isBlank() || hasNonLatinScript(rawName)) "Skin #$fallbackId" else rawName
}
