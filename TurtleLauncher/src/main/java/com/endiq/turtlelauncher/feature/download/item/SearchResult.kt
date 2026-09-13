package com.endiq.turtlelauncher.feature.download.item

/**
 * Records a search result.
 */
class SearchResult {
    var previousCount: Int = 0
    var totalResultCount: Int = 0
    val infoItems: MutableList<InfoItem> = ArrayList()
    var isLastPage: Boolean = false

    override fun toString(): String {
        return "SearchResult(previousCount=$previousCount, totalResultCount=$totalResultCount, infoItems=$infoItems, isLastPage=$isLastPage)"
    }
}