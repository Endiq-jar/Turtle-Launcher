package com.endiq.turtlelauncher.feature.skin

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import net.kdt.pojavlaunch.value.MinecraftAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * TurtleLauncher: real skin uploads for account types whose skins are NOT served by the
 * launcher's local skin server.
 *
 * Why this exists: LaunchArgs only attaches the local-skin authlib-injector javaagent for
 * LOCAL accounts. A Microsoft account launches with genuine Mojang auth (injecting a custom
 * auth server would break online play), and OtherLogin accounts (ely.by, Battly,
 * LittleSkin, custom Yggdrasil) launch with authlib-injector pointed at THEIR server - in
 * both cases the skin the game shows comes from the account's profile on those services,
 * so a skin saved into the launcher's per-account folder was silently ignored. That was
 * the "can't set skin on my Microsoft / ely.by account" bug.
 *
 * For Microsoft accounts the official route is used - the very same API the official
 * launcher calls (wiki.vg "Mojang API - Upload Skin"):
 * PUT https://api.minecraftservices.com/minecraft/profile/skins
 * with the account's bearer token and a multipart body (variant/model + png file). The
 * skin then applies everywhere Minecraft runs, not just this launcher.
 *
 * Third-party Yggdrasil servers have no common upload API (ely.by manages skins on its
 * website, Battly/LittleSkin have their own portals), so for those SkinCapeDialog offers
 * to open the server's skin site instead - see skinWebsiteFor().
 *
 * Blocking OkHttp calls - only ever invoke from a background Task, never the UI thread.
 */
object SkinUploader {

    private const val TAG = "SkinUploader"
    private const val SKINS_URL = "https://api.minecraftservices.com/minecraft/profile/skins"

    /** Sentinel result: the account's token was rejected (expired/revoked) - the caller
     *  should tell the user to refresh the account rather than showing a raw HTTP code. */
    const val EXPIRED = "__token_expired__"

    /**
     * Uploads [skinFile] as the profile skin of a Microsoft account.
     * @param slim true for the slim/Alex model, false for classic/Steve.
     * @return null on success, [EXPIRED] when the token was rejected, or a short
     *         human-readable error string otherwise. Never throws.
     */
    @JvmStatic
    fun uploadMicrosoft(account: MinecraftAccount, skinFile: File, slim: Boolean): String? {
        return try {
            if (!skinFile.isFile) return "Skin file is missing"
            val token = account.accessToken
            if (token.isNullOrEmpty() || token == "0") return "Account has no access token"

            val model = if (slim) "slim" else "classic"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // wiki.vg documents "variant" (classic/slim) for the minecraftservices
                // endpoint; the older api.mojang.com form used "model" (""/slim). Sending
                // both is harmless and covers either server-side generation.
                .addFormDataPart("variant", model)
                .addFormDataPart("model", if (slim) "slim" else "")
                .addFormDataPart(
                    "file", skinFile.name,
                    skinFile.asRequestBody("image/png".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(SKINS_URL)
                .put(body)
                .header("Authorization", "Bearer $token")
                .build()

            UrlManager.createOkHttpClient().newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        Logging.i(TAG, "Microsoft skin upload succeeded (${response.code})")
                        null
                    }
                    response.code == 401 || response.code == 403 -> {
                        Logging.w(TAG, "Microsoft skin upload rejected the token (${response.code})")
                        EXPIRED
                    }
                    else -> {
                        val detail = runCatching { response.body?.string()?.take(200) }.getOrNull()
                        Logging.e(TAG, "Microsoft skin upload failed: HTTP ${response.code} $detail")
                        "HTTP ${response.code}"
                    }
                }
            }
        } catch (t: Throwable) {
            Logging.e(TAG, "Microsoft skin upload threw", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * The website where an OtherLogin account's skin is actually managed, or null when the
     * server is unknown/not web-browsable. ely.by runs a dedicated skins system; Battly and
     * generic Yggdrasil servers fall back to their base URL.
     */
    @JvmStatic
    fun skinWebsiteFor(account: MinecraftAccount): String? {
        val base = account.otherBaseUrl ?: return null
        return when {
            base.contains("ely.by") -> "https://ely.by/skins"
            base.contains("battlylauncher.com") -> "https://battlylauncher.com"
            base.startsWith("http://") || base.startsWith("https://") -> base
            else -> null
        }
    }
}
