package com.endiq.turtlelauncher.feature.download

import com.endiq.turtlelauncher.feature.download.enums.Category
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.enums.Sort


/**
 * Provides the filter info used for platform searches.
 */
class Filters {
    var name: String = ""
    var mcVersion: String? = null
    var modloader: ModLoader? = null
    var sort: Sort = Sort.RELEVANT
    var category: Category = Category.ALL

    override fun toString(): String {
        return "Filters(name='$name', mcVersion=$mcVersion, modloader=$modloader, sort=$sort, category=$category)"
    }
}