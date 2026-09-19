package com.endiq.turtlelauncher.feature.turtle.heatmap

enum class PerfTier(val emoji: String, val label: String) {
    LIGHT("🟢", "Lightweight"),
    MODERATE("🟡", "Moderate"),
    HEAVY("🔴", "Heavy")
}

data class PerfEstimate(
    val tier: PerfTier,
    /** Uncompressed-size-based proxy for the item's RAM footprint, in KB. */
    val estimatedRamKb: Long,
    /** Decoded-texture-based proxy for GPU/VRAM footprint, in KB. 0 when not applicable. */
    val estimatedVramKb: Long,
    /** A raw structural signal used for the CPU side of the tier (mixin count, shader passes, etc). */
    val cpuSignal: Int,
    /** What [cpuSignal] actually counts, e.g. "mixins", "render passes". */
    val cpuSignalLabel: String,
    /** One short sentence explaining how the tier was derived - shown in the detail dialog. */
    val reasoning: String
) {
    fun formatRam(): String = formatKb(estimatedRamKb)
    fun formatVram(): String = if (estimatedVramKb <= 0) "n/a" else formatKb(estimatedVramKb)

    private fun formatKb(kb: Long): String =
        if (kb >= 1024) "~%.1f MB".format(kb / 1024.0) else "~$kb KB"
}
