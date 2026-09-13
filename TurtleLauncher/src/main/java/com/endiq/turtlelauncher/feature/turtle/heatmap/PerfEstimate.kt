package com.endiq.turtlelauncher.feature.turtle.heatmap

/**
 * TurtleLauncher: how heavy a mod/resource pack/shader pack is *likely* to be.
 *
 * IMPORTANT HONESTY NOTE: nothing here is a measured FPS number. This sandbox has no
 * device to actually run Minecraft on and profile, so [HeatmapAnalyzer] never invents
 * one either - "-12fps" style numbers would be fabricated, not estimated. What it does
 * instead is static-analyze the file itself for real, well-known correlates of runtime
 * cost (declared Mixin count for mods, render-pass count for shader packs, decoded
 * texture memory for resource packs) and bucket those into a tier. Treat the tier as a
 * "worth a second look" signal, not a guarantee.
 */
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
