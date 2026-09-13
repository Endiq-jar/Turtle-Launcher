package com.endiq.turtlelauncher.ui.subassembly.customcontrols

import kotlin.math.min

class ControlInfoData : Comparable<ControlInfoData?> {
    @JvmField
    var fileName: String? = null
    @JvmField
    var name: String = "null"
    @JvmField
    var version: String = "null"
    @JvmField
    var author: String = "null"
    @JvmField
    var desc: String = "null"

    override fun compareTo(other: ControlInfoData?): Int {
        // Sorting a list that contains null used to throw NPE out of the sort and
        // crash the caller - treat null as greater instead so the sort survives.
        other ?: return 1

        val thisName = this.fileName ?: this.name
        val otherName = other.fileName ?: other.name

        return compareChar(thisName, otherName)
    }

    private fun compareChar(first: String?, second: String?): Int {
        val a = first ?: ""
        val b = second ?: ""
        val firstLength = a.length
        val secondLength = b.length

        // Walk the characters of both strings.
        for (i in 0 until min(firstLength.toDouble(), secondLength.toDouble()).toInt()) {
            val firstChar = a[i].lowercaseChar()
            val secondChar = b[i].lowercaseChar()

            val compare = firstChar.compareTo(secondChar)
            if (compare != 0) {
                return compare
            }
        }

        return firstLength.compareTo(secondLength)
    }
}
