package com.endiq.turtlelauncher.feature.mod

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import okhttp3.Request

/**
 * Resolves a CurseForge mod's actual download URL from its (projectID, fileID) pair -
 * that's all a manifest.json ever gives you, unlike Modrinth's modrinth.index.json which
 * embeds a direct URL per file. CurseForge's v1 API requires a key (issued to registered
 * apps by Overwolf/CurseForge Core) for every request, including this one - there's no
 * keyless path anymore. TurtleLauncher doesn't ship one; this only does anything once the
 * person supplies their own via AllSettings.curseForgeApiKey.
 *
 * See CurseForgeModPackInstallHelper for how a missing/blank key is handled: overrides and
 * the mod loader still install fine either way, only the mod-jar downloads are skipped.
 */
internal object CurseForgeApi {
    private const val BASE = "https://api.curseforge.com/v1"
    private val client by lazy { UrlManager.createOkHttpClient() }

    data class ResolvedFile(
        val downloadUrl: String,
        val fileName: String,
        val sha1: String?,
        val fileLength: Long
    )

    /** Null on any failure - missing key, network error, file no longer exists, etc. All
     *  best-effort by design, same as the rest of this package's *DirectApi and *Api helpers. */
    fun resolveFile(modId: Long, fileId: Long, apiKey: String): ResolvedFile? {
        if (apiKey.isBlank()) return null

        return runCatching {
            val request = Request.Builder()
                .url("$BASE/mods/$modId/files/$fileId")
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logging.e("CurseForgeApi", "resolveFile($modId, $fileId) returned HTTP ${response.code}")
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                val data = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@use null

                val downloadUrl = data.get("downloadUrl")?.takeIf { !it.isJsonNull }?.asString
                    // Some files (author opted out of third-party download) have a null
                    // downloadUrl even with a valid key - nothing we can do about that one.
                    ?: return@use null
                val fileName = data.get("fileName")?.takeIf { !it.isJsonNull }?.asString ?: return@use null
                val fileLength = data.get("fileLength")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val sha1 = data.get("hashes")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.firstNotNullOfOrNull { hash ->
                        val obj = hash.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
                        // algo 1 == sha1 per CurseForge's API docs
                        if (obj.get("algo")?.asInt == 1) obj.get("value")?.asString else null
                    }

                ResolvedFile(downloadUrl, fileName, sha1, fileLength)
            }
        }.onFailure { e -> Logging.e("CurseForgeApi", "resolveFile($modId, $fileId) failed", e) }.getOrNull()
    }
}
