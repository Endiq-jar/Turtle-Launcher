package com.endiq.turtlelauncher.feature.version

import com.google.gson.annotations.SerializedName
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.log.Logging
import net.endiq.launcher.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Current game state info (supports migrating old configs).
 * @property version name of the currently selected version
 * @property favoritesMap favorite groups map <group name, contained versions>
 */
data class CurrentGameInfo(
    @SerializedName("version")
    var version: String = "",
    @SerializedName("favoritesInfo")
    val favoritesMap: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
) {
    /**
     * Atomically save the current state to disk.
     */
    fun saveCurrentInfo() {
        val infoFile = getInfoFile()
        runCatching {
            FileUtils.writeByteArrayToFile(
                infoFile,
                Tools.GLOBAL_GSON.toJson(this).toByteArray(Charsets.UTF_8)
            )
        }.onFailure { e ->
            Logging.e("CurrentGameInfo", "Save failed: ${infoFile.absolutePath}", e)
        }
    }

    companion object {
        private fun getInfoFile() = File(ProfilePathHome.getGameHome(), "CurrentInfo.cfg")

        private fun getLegacyInfoFile() = File(ProfilePathHome.getGameHome(), "CurrentVersion.cfg")

        /**
         * Refresh and return the latest game info (migrating old configs automatically).
         */
        fun refreshCurrentInfo(): CurrentGameInfo {
            val infoFile = getInfoFile()
            val legacyInfoFile = getLegacyInfoFile()

            return try {
                when {
                    infoFile.exists() -> loadFromJsonFile(infoFile)
                    legacyInfoFile.exists() -> migrateLegacyConfig(legacyInfoFile)
                    else -> createNewConfig()
                }
            } catch (e: Exception) {
                Logging.e("CurrentGameInfo", "Refresh failed", e)
                createNewConfig()
            }
        }

        private fun loadFromJsonFile(infoFile: File): CurrentGameInfo {
            return Tools.GLOBAL_GSON.fromJson(infoFile.readText(), CurrentGameInfo::class.java)
                .also { info -> checkNotNull(info) { "Deserialization returned null" } }
        }

        private fun migrateLegacyConfig(infoFile: File): CurrentGameInfo {
            return CurrentGameInfo().apply {
                version = infoFile.takeIf { it.exists() }?.readText() ?: ""
                infoFile.delete()
            }.applyPostActions()
        }

        private fun createNewConfig() = CurrentGameInfo().applyPostActions()

        private fun CurrentGameInfo.applyPostActions(): CurrentGameInfo {
            saveCurrentInfo()
            return this
        }
    }
}