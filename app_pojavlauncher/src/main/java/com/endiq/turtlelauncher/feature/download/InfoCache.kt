package com.endiq.turtlelauncher.feature.download

import com.endiq.turtlelauncher.feature.download.item.DependenciesInfoItem
import com.endiq.turtlelauncher.feature.download.item.ModLikeVersionItem
import com.endiq.turtlelauncher.feature.download.item.ModVersionItem
import com.endiq.turtlelauncher.feature.download.item.VersionItem


/**
 * Cache search results in memory so the next load can reuse them directly.
 */
class InfoCache {
    abstract class CacheBase<V> {
        private val cache: MutableMap<String, V> = HashMap()

        /**
         * Store a looked-up value in memory, keyed by mod id.
         */
        fun put(modId: String, value: V) {
            cache[modId] = value
        }

        /**
         * Read a stored value by mod id, or null when absent.
         */
        fun get(modId: String): V? {
            return cache[modId]
        }

        /**
         * Check whether a mod id is already cached in memory.
         */
        fun containsKey(modId: String): Boolean {
            return cache.containsKey(modId)
        }
    }

    object DependencyInfoCache : CacheBase<DependenciesInfoItem>()
    object VersionCache : CacheBase<MutableList<VersionItem>>()
    object ModVersionCache : CacheBase<MutableList<ModVersionItem>>()
    object ModPackVersionCache : CacheBase<MutableList<ModLikeVersionItem>>()
}