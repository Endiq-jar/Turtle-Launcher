package com.endiq.turtlelauncher.feature.download.utils

import net.endiq.launcher.JMinecraftVersionList

/**
 * Groups the flat Mojang version manifest into per-series buckets for the version-card
 * screen: one card per major.minor series (e.g. "1.21", "26.2"), each containing every
 * release/snapshot/pre-release/rc that belongs to it, plus dedicated Beta and Alpha cards
 * for the pre-1.0 eras (those don't have a meaningful numeric series of their own).
 *
 * Series membership is derived from the version id string itself, not a lookup table
 * Mojang doesn't publish one - so it only works when the id actually encodes its target
 * series. That covers every id shape actually seen in this game's manifest:
 *   - "1.21.4"            (release)               -> series "1.21"
 *   - "26.2"               (release, 2-segment)     -> series "26.2"
 *   - "1.21.4-pre1"/"-rc1" (pre-release/RC)         -> series "1.21"
 *   - "26.3 Snapshot 4"    (this game's snapshot naming - the series is spelled out) -> "26.3"
 * Anything that doesn't match one of those shapes (old week-coded snapshot names with no
 * version number in them at all, like real Minecraft's historical "24w14a") can't be
 * mapped to a series from the id alone and is deliberately left out of the numbered-series
 * grouping rather than guessed at - see [ungroupedSnapshots].
 */
object VersionSeriesUtils {

    /** Same sticky-event lookup VersionListView already used - kept in one place now that
     *  two screens need it (the card grid and the per-series detail screen). */
    fun fetchAllVersions(): List<JMinecraftVersionList.Version> {
        val event = org.greenrobot.eventbus.EventBus.getDefault()
            .getStickyEvent(com.endiq.turtlelauncher.event.sticky.MinecraftVersionValueEvent::class.java)
        val list = event?.list?.versions ?: return emptyList()
        return list.toList()
    }

    data class SeriesCard(
        val seriesLabel: String,
        val versions: List<JMinecraftVersionList.Version>,
        /** Newest releaseTime among this series' versions - drives sort order (newest first). */
        val sortKey: String?
    )

    private val LEADING_VERSION_NUMBER = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""")

    /**
     * @return series id like "1.21" or "26.2", or null if [versionId] doesn't encode a
     * recognizable version number anywhere at the start of the string.
     */
    fun extractSeries(versionId: String): String? {
        val match = LEADING_VERSION_NUMBER.find(versionId.trim()) ?: return null
        val major = match.groupValues[1]
        val minor = match.groupValues[2]
        return "$major.$minor"
    }

    /**
     * Splits [versions] into: numbered series cards (newest series first), a Beta card,
     * an Alpha card, and whatever couldn't be assigned a series at all.
     */
    fun group(versions: List<JMinecraftVersionList.Version>): Grouped {
        val beta = mutableListOf<JMinecraftVersionList.Version>()
        val alpha = mutableListOf<JMinecraftVersionList.Version>()
        val ungrouped = mutableListOf<JMinecraftVersionList.Version>()
        val bySeries = LinkedHashMap<String, MutableList<JMinecraftVersionList.Version>>()

        versions.forEach { version ->
            when (version.type) {
                "old_beta" -> beta.add(version)
                "old_alpha" -> alpha.add(version)
                else -> {
                    val series = extractSeries(version.id)
                    if (series != null) {
                        bySeries.getOrPut(series) { mutableListOf() }.add(version)
                    } else {
                        ungrouped.add(version)
                    }
                }
            }
        }

        val cards = bySeries.map { (series, list) ->
            SeriesCard(series, list, list.maxOfOrNull { it.releaseTime ?: "" })
        }.sortedWith(compareByDescending<SeriesCard> { it.sortKey ?: "" }.thenByDescending { seriesSortValue(it.seriesLabel) })

        return Grouped(cards, beta, alpha, ungrouped)
    }

    /** Numeric fallback sort when two series share a release time (shouldn't normally happen). */
    private fun seriesSortValue(series: String): Double =
        series.split(".").let { parts ->
            val major = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val minor = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            major * 10000 + minor
        }

    data class Grouped(
        val seriesCards: List<SeriesCard>,
        val betaVersions: List<JMinecraftVersionList.Version>,
        val alphaVersions: List<JMinecraftVersionList.Version>,
        /** Snapshot/release ids with no parseable version number - not shown as their own
         *  card by default, but kept here rather than silently dropped. */
        val ungroupedSnapshots: List<JMinecraftVersionList.Version>
    )
}
