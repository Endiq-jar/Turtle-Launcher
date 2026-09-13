package com.endiq.turtlelauncher.feature.skin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.DownloadUtils

/**
 * Looks up a player's current Mojang skin and LabyMod cape by username, for the
 * "Browse Skin/Cape" flow in [com.endiq.turtlelauncher.ui.dialog.SkinCapeDialog].
 *
 * LabyMod doesn't host alternate/browsable skins of its own - a player's skin is
 * always just their regular Mojang skin. What LabyMod *does* provide is a public,
 * no-auth-required cape lookup (the same one CustomSkinLoader/OptiFine-adjacent
 * tools have used for years): http://capes.labymod.net/capes/<uuid-with-dashes>.png,
 * which returns a non-2xx response if the player has no LabyMod cape. So "browsing
 * LabyMod" in practice means: resolve the username to a UUID via Mojang, then check
 * that one cape URL - there's no gallery of alternate skins the way a site like
 * NameMC has, just "this player's current skin" + "this player's LabyMod cape".
 *
 * Every function here is best-effort and does blocking network calls - callers
 * must invoke off the main thread (see SkinCapeDialog's use of Task.runTask).
 */
internal object LabyModSkinApi {
    private const val MOJANG_UUID_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/"
    private const val MOJANG_SESSION_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/"
    private const val LABYMOD_CAPE = "http://capes.labymod.net/capes/"

    private val client by lazy { UrlManager.createOkHttpClient() }

    data class PlayerLookup(
        val username: String,
        val uuidDashed: String,
        val skinUrl: String?,
        val isSlim: Boolean,
        val capeUrl: String?
    )

    /**
     * Full lookup used by the dialog: username -> UUID -> skin + LabyMod cape.
     * Returns null only if the username itself doesn't resolve to an account; a
     * resolved player with no LabyMod cape still returns a result, just with
     * [PlayerLookup.capeUrl] == null (checked by the dialog to show "no cape found").
     */
    fun lookupPlayer(username: String): PlayerLookup? {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) return null

        val uuid = lookupUuid(trimmed) ?: return null
        val (skinUrl, isSlim) = fetchSkin(uuid)
        val capeUrl = fetchLabyModCape(uuid)
        return PlayerLookup(trimmed, uuid, skinUrl, isSlim, capeUrl)
    }

    /**
     * Resolves [username] to a dashed UUID via Mojang's username lookup. Returns
     * null if the name doesn't resolve to an account (typo, never existed, etc).
     */
    private fun lookupUuid(username: String): String? = runCatching {
        val request = UrlManager.createRequestBuilder(MOJANG_UUID_LOOKUP + username).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return@use null
            val rawId = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@use null
            dashUuid(rawId)
        }
    }.onFailure { e -> Logging.e("LabyModSkinApi", "Username lookup failed for $username", e) }.getOrNull()

    private fun dashUuid(raw: String): String {
        if (raw.contains("-")) return raw
        if (raw.length != 32) return raw
        return buildString {
            append(raw, 0, 8); append('-')
            append(raw, 8, 12); append('-')
            append(raw, 12, 16); append('-')
            append(raw, 16, 20); append('-')
            append(raw, 20, 32)
        }
    }

    /** Fetches the player's current skin URL + slim/classic variant from Mojang. */
    private fun fetchSkin(uuidDashed: String): Pair<String?, Boolean> = runCatching {
        val uuidNoDash = uuidDashed.replace("-", "")
        val profileJson = DownloadUtils.downloadString(MOJANG_SESSION_PROFILE + uuidNoDash)
        val profileObject = Tools.GLOBAL_GSON.fromJson(profileJson, JsonObject::class.java)
        val properties = profileObject?.get("properties")?.takeIf { it.isJsonArray }?.asJsonArray
        val rawValue = properties?.takeIf { it.size() > 0 }?.get(0)?.asJsonObject?.get("value")?.asString
            ?: return@runCatching null to false

        val value = StringUtils.decodeBase64(rawValue)
        val valueObject = Tools.GLOBAL_GSON.fromJson(value, JsonObject::class.java)
        val skinObject = valueObject?.get("textures")?.asJsonObject?.get("SKIN")?.takeIf { !it.isJsonNull }?.asJsonObject
        val url = skinObject?.get("url")?.asString
        val slim = skinObject?.get("metadata")?.asJsonObject
            ?.get("model")?.asString == "slim"
        url to slim
    }.onFailure { e -> Logging.e("LabyModSkinApi", "Skin fetch failed for $uuidDashed", e) }.getOrDefault(null to false)

    /** Checks whether the player has a LabyMod cape registered; null if not. */
    private fun fetchLabyModCape(uuidDashed: String): String? = runCatching {
        val url = LABYMOD_CAPE + uuidDashed + ".png"
        val request = UrlManager.createRequestBuilder(url).head().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) url else null
        }
    }.onFailure { e -> Logging.e("LabyModSkinApi", "Cape check failed for $uuidDashed", e) }.getOrNull()
}
