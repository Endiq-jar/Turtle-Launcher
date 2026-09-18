package com.endiq.turtlelauncher.feature.skin

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import net.endiq.launcher.value.MinecraftAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object SkinUploader {

    private const val TAG = "SkinUploader"
    private const val SKINS_URL = "https://api.minecraftservices.com/minecraft/profile/skins"

    /** Sentinel result: the account's token was rejected (expired/revoked) - the caller
     *  should tell the user to refresh the account rather than showing a raw HTTP code. */
    const val EXPIRED = "__token_expired__"

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
