package com.endiq.turtlelauncher.feature.version.favorites

import com.endiq.turtlelauncher.feature.version.VersionsManager
import java.util.concurrent.ConcurrentHashMap

class FavoritesVersionUtils private constructor() {
    companion object {
        private inline fun modifyFavorites(action: (MutableMap<String, MutableSet<String>>) -> Unit) {
            VersionsManager.currentGameInfo.apply {
                action(favoritesMap)
                saveCurrentInfo()
            }
        }

        /**
         * Rename a version atomically.
         */
        fun renameVersion(oldName: String, newName: String) = modifyFavorites { map ->
            map.values.forEach { versions ->
                if (oldName in versions) {
                    versions.remove(oldName)
                    versions.add(newName)
                }
            }
        }

        /**
         * Add a favorite group.
         */
        fun addFolder(name: String) = modifyFavorites { map ->
            map.putIfAbsent(name, ConcurrentHashMap.newKeySet())
        }

        /**
         * Remove a favorite group.
         */
        fun removeFolder(name: String) = modifyFavorites { map ->
            map.remove(name)
        }

        /**
         * Update the version favorites.
         * @param version the target version
         * @param targetFolders favorite groups that should contain this version
         */
        fun updateVersionFolders(version: String, targetFolders: Set<String>) = modifyFavorites { map ->
            // Add to the target favorite group.
            targetFolders.forEach { folder ->
                map.getOrPut(folder) { ConcurrentHashMap.newKeySet() }.add(version)
            }

            // Remove from every non-target favorite group.
            map.keys.filterNot { it in targetFolders }.forEach { folder ->
                map[folder]?.remove(version)
            }
        }

        /**
         * Get the valid favorite-group structure.
         */
        fun getFavoritesStructure(): Map<String, Set<String>> =
            VersionsManager.currentGameInfo.favoritesMap.let { map ->
                map.entries.associate { (k, v) -> k to v.toSet() }
            }

        /**
         * Get the valid versions of a favorite group.
         */
        fun getValidVersions(folder: String): Set<String> =
            VersionsManager.currentGameInfo.favoritesMap[folder]
                ?.filter { VersionsManager.checkVersionExistsByName(it) }
                .orEmpty()
                .toSet()
    }
}