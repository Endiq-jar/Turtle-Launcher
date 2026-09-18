package com.endiq.turtlelauncher.feature.download.utils

import net.endiq.launcher.JMinecraftVersionList

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
        val ungroupedSnapshots: List<JMinecraftVersionList.Version>
    )
}
