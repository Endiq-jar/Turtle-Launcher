package com.endiq.turtlelauncher.utils.skin

import com.google.gson.JsonObject
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.DownloadUtils
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class SkinFileDownloader {
    private val mClient = UrlManager.createOkHttpClient()

    /**
     * Try to download the yggdrasil skin.
     */
    @Throws(Exception::class)
    fun yggdrasil(url: String, skinFile: File, uuid: String) {
        val profileJson = DownloadUtils.downloadString("${url.removeSuffix("/")}/session/minecraft/profile/$uuid")
        val profileObject = Tools.GLOBAL_GSON.fromJson(profileJson, JsonObject::class.java)
        val properties = profileObject.get("properties").asJsonArray
        val rawValue = properties.get(0).asJsonObject.get("value").asString

        val value = StringUtils.decodeBase64(rawValue)

        val valueObject = Tools.GLOBAL_GSON.fromJson(value, JsonObject::class.java)
        val skinUrl = valueObject.get("textures").asJsonObject.get("SKIN").asJsonObject.get("url").asString

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