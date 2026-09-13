package com.endiq.turtlelauncher.feature.mod

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder

/**
 * Small, self-contained client for the handful of public Modrinth v2 API endpoints
 * needed by [ModDependencyResolver] and [ModUpdateChecker]. Deliberately bypasses the
 * heavier platform-helper/ApiHandler abstraction used by the mod browser UI — this is
 * just a few stateless GET/POST calls and a file download, used from background
 * maintenance tasks where simplicity and predictable failure handling matter more
 * than feature parity with the full download browser.
 *
 * Every public function is best-effort: network/parse failures are caught and turned
 * into null/empty results rather than thrown, so callers never need defensive
 * try/catch of their own.
 */
internal object ModrinthDirectApi {
    private const val BASE = "https://api.modrinth.com/v2"
    private val client by lazy { UrlManager.createOkHttpClient() }

    private fun encodeJsonStringArray(values: List<String>): String {
        val json = values.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        return URLEncoder.encode("[$json]", "UTF-8")
    }

    private fun getObject(url: String): JsonObject? {
        return runCatching {
            val request = UrlManager.createRequestBuilder(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            }
        }.onFailure { e -> Logging.e("ModrinthDirectApi", "GET (object) failed: $url", e) }.getOrNull()
    }

    private fun getArray(url: String): JsonArray? {
        return runCatching {
            val request = UrlManager.createRequestBuilder(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JsonParser.parseString(body).takeIf { it.isJsonArray }?.asJsonArray
            }
        }.onFailure { e -> Logging.e("ModrinthDirectApi", "GET (array) failed: $url", e) }.getOrNull()
    }

    /**
     * Finds the newest version of [projectIdOrSlug] compatible with [mcVersion] + [loader].
     * Modrinth returns versions newest-first, so the first entry (if any) is what we want.
     *
     * A mod's own metadata ID (fabric.mod.json "id", mods.toml "modId", etc.) frequently does
     * NOT match its actual Modrinth project slug (e.g. "cloth-config" ships as modid
     * "cloth-config2"). Previously this call failed outright (404) whenever that happened,
     * which was the main reason the dependency installer silently resolved almost nothing.
     * Now: try the id/slug directly first (fast path, works for the majority of mods), and
     * if that 404s, fall back to Modrinth's search endpoint to locate the real project slug.
     */
    fun findCompatibleVersion(projectIdOrSlug: String, mcVersion: String, loader: ModLoader?): JsonObject? {
        val loaders = loader?.let { encodeJsonStringArray(listOf(it.modrinthName)) }
        val gameVersions = encodeJsonStringArray(listOf(mcVersion))

        versionsFor(projectIdOrSlug, loaders, gameVersions)?.let { return it }

        // The slug-guessing fallback is mod-specific (searches project_type:mod), so it
        // only makes sense — and is only needed — when we actually have a loader to go
        // with it. Resource packs/shader packs always call this with a project_id we
        // already got from a hash lookup, so the direct versionsFor() above is enough.
        val resolvedSlug = loader?.let { searchForProjectSlug(projectIdOrSlug, it) } ?: return null
        if (resolvedSlug == projectIdOrSlug) return null // already tried, genuinely not found
        return versionsFor(resolvedSlug, loaders, gameVersions)
    }

    private fun versionsFor(idOrSlug: String, loaders: String?, gameVersions: String): JsonObject? {
        val safeId = URLEncoder.encode(idOrSlug, "UTF-8")
        val loaderParam = loaders?.let { "&loaders=$it" } ?: ""
        val url = "$BASE/project/$safeId/version?game_versions=$gameVersions$loaderParam"
        val versions = getArray(url) ?: return null
        return versions.firstOrNull { it.isJsonObject }?.asJsonObject
    }

    /**
     * Best-effort lookup of a project's real slug by its mod-loader ID via Modrinth's search
     * endpoint, restricted to the "mod" project type and the given loader. Picks the result
     * whose slug or project_id case-insensitively equals [modId] if present, otherwise the
     * top search hit (Modrinth's relevance ranking for an exact-id query is reliable enough
     * for this best-effort resolution).
     */
    private fun searchForProjectSlug(modId: String, loader: ModLoader): String? {
        val query = URLEncoder.encode(modId, "UTF-8")
        val facets = URLEncoder.encode(
            "[[\"project_type:mod\"],[\"categories:${loader.modrinthName}\"]]", "UTF-8"
        )
        val url = "$BASE/search?query=$query&facets=$facets&limit=5"
        val obj = getObject(url) ?: return null
        val hits = obj.getAsJsonArray("hits")?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            ?: return null
        if (hits.isEmpty()) return null

        val exact = hits.firstOrNull { hit ->
            val slug = hit.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
            val pid = hit.get("project_id")?.takeIf { it.isJsonPrimitive }?.asString
            slug.equals(modId, ignoreCase = true) || pid.equals(modId, ignoreCase = true)
        }
        val chosen = exact ?: hits.first()
        return chosen.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
            ?: chosen.get("project_id")?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** Bulk hash → version lookup, used by the update checker. Returns hash -> version JSON object. */
    fun lookupVersionsByHash(hashes: List<String>): Map<String, JsonObject> {
        if (hashes.isEmpty()) return emptyMap()
        return runCatching {
            val bodyJson = JsonObject().apply {
                add("hashes", JsonArray().apply { hashes.forEach { add(it) } })
                addProperty("algorithm", "sha1")
            }
            val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val request = UrlManager.createRequestBuilder("$BASE/version_files", body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap()
                val text = response.body?.string() ?: return@use emptyMap()
                val obj = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: return@use emptyMap()
                obj.entrySet().mapNotNull { (key, value) ->
                    if (value.isJsonObject) key to value.asJsonObject else null
                }.toMap()
            }
        }.onFailure { e -> Logging.e("ModrinthDirectApi", "version_files lookup failed", e) }.getOrDefault(emptyMap())
    }

    /** Picks the "primary" file from a Modrinth version object, falling back to the first file. */
    fun primaryFileOf(versionObj: JsonObject): JsonObject? {
        val files = versionObj.getAsJsonArray("files") ?: return null
        return files.firstOrNull { it.isJsonObject && it.asJsonObject.get("primary")?.asBoolean == true }?.asJsonObject
            ?: files.firstOrNull { it.isJsonObject }?.asJsonObject
    }

    /** Streams [url] straight into [targetFile], creating parent directories as needed. */
    @Throws(IOException::class)
    fun downloadTo(url: String, targetFile: File) {
        val request = UrlManager.createRequestBuilder(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} while downloading $url")
            val body = response.body ?: throw IOException("Empty response body for $url")
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { out -> body.byteStream().copyTo(out) }
        }
    }

    /**
     * Finds the newest version of [modIdOrSlug] compatible with [mcVersion]/[loader] and
     * downloads its primary file into [destFolder]. Returns a short human-readable
     * "filename (version)" description on success, or null if nothing could be resolved
     * or downloaded.
     */
    fun downloadBestMatch(modIdOrSlug: String, mcVersion: String, loader: ModLoader?, destFolder: File): String? {
        val versionObj = findCompatibleVersion(modIdOrSlug, mcVersion, loader) ?: return null
        val primaryFile = primaryFileOf(versionObj) ?: return null
        val downloadUrl = primaryFile.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val fileName = primaryFile.get("filename")?.takeIf { it.isJsonPrimitive }?.asString ?: "$modIdOrSlug.jar"
        val targetFile = File(destFolder, fileName)
        if (targetFile.exists()) return "$fileName (already installed)" // don't overwrite, but don't report as failed either

        return runCatching {
            downloadTo(downloadUrl, targetFile)
            val versionName = versionObj.get("version_number")?.takeIf { it.isJsonPrimitive }?.asString
                ?: versionObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?: ""
            if (versionName.isNotBlank()) "$fileName ($versionName)" else fileName
        }.onFailure { e ->
            Logging.e("ModrinthDirectApi", "Failed to download dependency '$modIdOrSlug'", e)
            targetFile.delete()
        }.getOrNull()
    }
}
