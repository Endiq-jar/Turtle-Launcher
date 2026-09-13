package com.endiq.turtlelauncher.feature.version

import com.endiq.turtlelauncher.feature.log.Logging
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.FileWriter

class VersionInfo(
    val minecraftVersion: String,
    val loaderInfo: Array<LoaderInfo>?
) {
    /**
     * Assemble the Minecraft version info, including ModLoader info.
     * @return the info strings joined with ", "
     */
    fun getInfoString(): String {
        val infoList = mutableListOf<String>().apply {
            add(minecraftVersion)
            loaderInfo?.forEach { info ->
                when {
                    info.name.isNotBlank() && info.version.isNotBlank() -> add("${info.name} - ${info.version}")
                    info.name.isNotBlank() -> add(info.name)
                    info.version.isNotBlank() -> add(info.version)
                }
            }
        }
        return infoList.joinToString(", ")
    }

    data class LoaderInfo(
        val name: String,
        val version: String
    ) {
        /**
         * Map a loader name to its environment variable key.
         */
        fun getLoaderEnvKey(): String? {
            return when(name) {
                "OptiFine" -> "INST_OPTIFINE"
                "Forge" -> "INST_FORGE"
                "NeoForge" -> "INST_NEOFORGE"
                "Fabric" -> "INST_FABRIC"
                "Quilt" -> "INST_QUILT"
                "LiteLoader" -> "INST_LITELOADER"
                else -> null
            }
        }
    }

    fun save(versionFolder: File) {
        runCatching {
            val turtleVersionPath = VersionsManager.getTurtleVersionPath(versionFolder)
            val infoFile = File(turtleVersionPath, "VersionInfo.json")
            if (!turtleVersionPath.exists()) turtleVersionPath.mkdirs()

            FileWriter(infoFile, false).use {
                val json = Tools.GLOBAL_GSON.toJson(this)
                it.write(json)
            }
        }.onFailure { e -> Logging.e("Save Version Info", Tools.printToString(e)) }
    }
}