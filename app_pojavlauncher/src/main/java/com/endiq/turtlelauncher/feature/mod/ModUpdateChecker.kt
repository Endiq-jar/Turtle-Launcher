package com.endiq.turtlelauncher.feature.mod

import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.parser.ModInfo
import com.endiq.turtlelauncher.utils.file.FileTools
import java.io.File


object ModUpdateChecker {

    data class UpdateInfo(
        val currentFile: File,
        val modName: String,
        val currentVersion: String,
        val newVersionNumber: String,
        val downloadUrl: String,
        val newFileName: String
    )

    /**
     * @param modInfoList every mod already parsed for this version.
     * @param mcVersion   e.g. "1.21.5".
     * @param loader      the version's mod loader, or null if unknown (skips entirely).
     */
    fun checkForUpdates(modInfoList: List<ModInfo>, mcVersion: String, loader: ModLoader?): List<UpdateInfo> {
        if (loader == null || loader == ModLoader.ALL) return emptyList()
        val jarFiles = modInfoList.mapNotNull { it.file }
        return checkForFileUpdates(jarFiles, mcVersion, loader)
    }

    /**
     * Same hash-based check as [checkForUpdates], but for any flat list of files instead
     * of parsed mods — resource packs and shader packs don't go through mod parsing and
     * have no loader of their own, so [loader] is optional here (pass null to check
     * across all loaders, which is what you want for those two).
     */
    fun checkForFileUpdates(files: List<File>, mcVersion: String, loader: ModLoader?): List<UpdateInfo> {
        if (mcVersion.isBlank()) return emptyList()

        val validFiles = files.filter { it.exists() && it.isFile }
        if (validFiles.isEmpty()) return emptyList()

        val hashToFile = LinkedHashMap<String, File>()
        validFiles.forEach { file ->
            runCatching { FileTools.calculateFileHash(file, "SHA-1") }
                .onSuccess { hash -> hashToFile[hash] = file }
                .onFailure { e -> Logging.e("ModUpdateChecker", "Failed to hash ${file.name}", e) }
        }
        if (hashToFile.isEmpty()) return emptyList()

        val hashLookup = runCatching { ModrinthDirectApi.lookupVersionsByHash(hashToFile.keys.toList()) }
            .onFailure { e -> Logging.e("ModUpdateChecker", "version_files lookup failed", e) }
            .getOrDefault(emptyMap())
        if (hashLookup.isEmpty()) return emptyList()

        val updates = mutableListOf<UpdateInfo>()
        hashToFile.forEach { (hash, file) ->
            val currentVersionObj = hashLookup[hash] ?: return@forEach
            val projectId = currentVersionObj.get("project_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val currentId = currentVersionObj.get("id")?.takeIf { it.isJsonPrimitive }?.asString

            runCatching {
                val latest = ModrinthDirectApi.findCompatibleVersion(projectId, mcVersion, loader) ?: return@forEach
                val latestId = latest.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                if (latestId == null || latestId == currentId) return@forEach

                val primaryFile = ModrinthDirectApi.primaryFileOf(latest) ?: return@forEach
                val url = primaryFile.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val fileName = primaryFile.get("filename")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach

                updates.add(
                    UpdateInfo(
                        currentFile = file,
                        modName = currentVersionObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: file.name,
                        currentVersion = currentVersionObj.get("version_number")?.takeIf { it.isJsonPrimitive }?.asString ?: "?",
                        newVersionNumber = latest.get("version_number")?.takeIf { it.isJsonPrimitive }?.asString ?: "?",
                        downloadUrl = url,
                        newFileName = fileName
                    )
                )
            }.onFailure { e -> Logging.e("ModUpdateChecker", "Failed to check update for project $projectId", e) }
        }

        return updates
    }

    /** Downloads & swaps in every update in [updates], deleting the old jar on success. Returns (succeeded, failed). */
    fun applyUpdates(updates: List<UpdateInfo>): Pair<Int, Int> {
        var success = 0
        var failed = 0
        updates.forEach { update ->
            runCatching {
                val targetFile = File(update.currentFile.parentFile, update.newFileName)
                ModrinthDirectApi.downloadTo(update.downloadUrl, targetFile)
                if (targetFile.exists() && targetFile.length() > 0 && targetFile.absolutePath != update.currentFile.absolutePath) {
                    update.currentFile.delete()
                }
                success++
            }.onFailure { e ->
                Logging.e("ModUpdateChecker", "Failed to apply update for ${update.modName}", e)
                failed++
            }
        }
        return success to failed
    }
}
