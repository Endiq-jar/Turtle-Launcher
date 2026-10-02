package com.endiq.turtlelauncher.feature.download

import com.endiq.turtlelauncher.feature.download.item.DependenciesInfoItem
import com.endiq.turtlelauncher.feature.download.item.ModLikeVersionItem
import com.endiq.turtlelauncher.feature.download.item.ModVersionItem
import com.endiq.turtlelauncher.feature.download.item.VersionItem
import java.util.concurrent.ConcurrentHashMap


/**
 * Cache search results in memory so the next load can reuse them directly.
 */
class InfoCache {
    abstract class CacheBase<V> {
        // ConcurrentHashMap, not HashMap: entries are written from the download worker
        // threads (the Modrinth helpers) while [clear] can be called from the main thread by
        // the pre-launch cleanup in BackgroundServiceManager.onGameSessionStart.
        private val cache: MutableMap<String, V> = ConcurrentHashMap()

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

        /**
         * Drop every entry. Reversible by design - this is a lookup cache keyed by mod id, so
         * the only effect is that the next lookup goes back to the network.
         */
        fun clear() {
            cache.clear()
        }
    }

    object DependencyInfoCache : CacheBase<DependenciesInfoItem>()
    object VersionCache : CacheBase<MutableList<VersionItem>>()
    object ModVersionCache : CacheBase<MutableList<ModVersionItem>>()
    object ModPackVersionCache : CacheBase<MutableList<ModLikeVersionItem>>()

    /**
     * Releases every in-memory download/search cache this launcher keeps. Called before a
     * game session (see BackgroundServiceManager) so those results aren't holding RAM the
     * game JVM could be using; nothing on disk is touched.
     */
    @JvmStatic
    fun clearAll() {
        DependencyInfoCache.clear()
        VersionCache.clear()
        ModVersionCache.clear()
        ModPackVersionCache.clear()
    }
}
