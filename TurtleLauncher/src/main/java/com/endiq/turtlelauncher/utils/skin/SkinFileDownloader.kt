package com.endiq.turtlelauncher.utils.skin

import com.google.gson.JsonObject
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.endiq.launcher.Tools
import net.endiq.launcher.utils.DownloadUtils
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class SkinFileDownloader {
    private val mClient = UrlManager.createOkHttpClient()

    /**
     * Try to download the yggdrasil skin.
     *
     * TurtleLauncher: an account with no skin set (freshly created ely.by/authlib-injector
     * accounts, or a cape-only profile) has no "properties" entry, or a "textures" object with
     * no "SKIN" key at all - Gson's JsonObject.get() returns null (not JsonNull) for a missing
     * key, and .asJsonObject on that null crashed with an NPE ("Could not update skin" in the
     * logs). That is a normal, common profile shape, not an error - treat it as "nothing to
     * download" instead of throwing.
     */
    @Throws(Exception::class)
    fun yggdrasil(url: String, skinFile: File, uuid: String) {
        val profileJson = DownloadUtils.downloadString("${url.removeSuffix("/")}/session/minecraft/profile/$uuid")
        val profileObject = Tools.GLOBAL_GSON.fromJson(profileJson, JsonObject::class.java)
        val rawValue = profileObject?.get("properties")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("value")?.takeIf { it.isJsonPrimitive }?.asString
            ?: run {
                Logging.i("SkinFileDownloader", "Profile has no skin properties, skipping: $uuid")
                return
            }

        val value = StringUtils.decodeBase64(rawValue)
        val valueObject = Tools.GLOBAL_GSON.fromJson(value, JsonObject::class.java)
        val skinUrl = valueObject?.get("textures")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("SKIN")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?: run {
                Logging.i("SkinFileDownloader", "Account has no skin texture set, skipping: $uuid")
                return
            }

        downloadSkin(skinUrl, skinFile)
    }

    private fun downloadSkin(url: String, skinFile: File) {
        skinFile.parentFile?.apply {
            if (!exists()) mkdirs()
        }

        val request = Request.Builder()
            .url(url)
            .build()

        mClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Unexpected code $response")
            }

            try {
                response.body?.byteStream()?.use { inputStream ->
                    FileOutputStream(skinFile).use { outputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }
            } catch (e: Exception) {
                Logging.e("SkinFileDownloader", "Failed to download skin file", e)
            }
        }
    }
}