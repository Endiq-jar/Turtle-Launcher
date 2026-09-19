package com.endiq.turtlelauncher.feature.mod.modloader

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonSyntaxException
import com.endiq.turtlelauncher.feature.log.Logging.e
import com.endiq.turtlelauncher.feature.version.install.Addon
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.FabricVersion
import net.kdt.pojavlaunch.utils.DownloadUtils
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class FabricLikeUtils private constructor(
    val webUrl: String,
    val mcModUrl: String,
    private val mApiUrl: String,
    val iconName: String,
    val addon: Addon
) {
    @Throws(IOException::class)
    fun downloadGameVersions(force: Boolean): Array<FabricVersion>? {
        try {
            return DownloadUtils.downloadStringCached(
                String.format(
                    GAME_METADATA_URL,
                    mApiUrl
                ), "${iconName}_game_versions", force
            ) { jsonArrayIn: String ->
                deserializeRawVersions(
                    jsonArrayIn
                )
            }
        } catch (ignored: DownloadUtils.ParseException) {
        }
        return null
    }

    @Throws(IOException::class)
    fun downloadLoaderVersions(force: Boolean): Array<FabricVersion>? {
        try {
            return DownloadUtils.downloadStringCached(
                String.format(
                    LOADER_METADATA_URL,
                    mApiUrl
                ),
                iconName + "_loader_versions", force
            ) { input: String ->
                try {
                    return@downloadStringCached deserializeLoaderVersions(
                        input
                    )
                } catch (e: JSONException) {
                    throw DownloadUtils.ParseException(e)
                }
            }
        } catch (e: DownloadUtils.ParseException) {
            e("Download Fabric Meta", "Failed to parse loader version list", e)
        }
        return null
    }

    @get:Throws(Exception::class)
    val installerDownloadUrl: String
        get() {
            val jsonString = DownloadUtils.downloadStringCached(
                String.format(
                    INSTALLER_METADATA_URL,
                    mApiUrl
                ),
                iconName + "_installer", false
            ) { input: String? -> input }

            val jsonArray = Gson().fromJson(jsonString, JsonArray::class.java)
                ?: throw DownloadUtils.ParseException(null)
            if (jsonArray.size() == 0) throw DownloadUtils.ParseException(null)
            val jsonObject = jsonArray[0].asJsonObject // always take the newest installer info
            val url = jsonObject["url"]?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw DownloadUtils.ParseException(null)
            println(url)

            return url
        }

    fun createJsonDownloadUrl(gameVersion: String?, loaderVersion: String?): String {
        val newGameVersion: String?
        val newLoaderVersion: String?
        try {
            newGameVersion = URLEncoder.encode(gameVersion, "UTF-8")
            newLoaderVersion = URLEncoder.encode(loaderVersion, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            throw RuntimeException(e)
        }
        return String.format(
            JSON_DOWNLOAD_URL,
            mApiUrl, newGameVersion, newLoaderVersion
        )
    }

    val name: String get() = addon.addonName

    fun getDownloadTask(gameVersion: String?, loaderVersion: String?): FabricLikeDownloadTask {
        return if ("Fabric" == addon.addonName) {
            FabricLikeDownloadTask(this)
        } else FabricLikeDownloadTask(this, gameVersion, loaderVersion)
    }

    companion object {
        val FABRIC_UTILS: FabricLikeUtils = FabricLikeUtils(
            "https://fabricmc.net/",
            "https://www.mcmod.cn/class/1411.html",
            "https://meta.fabricmc.net/v2",
            "fabric",
            Addon.FABRIC
        )
        val QUILT_UTILS: FabricLikeUtils = FabricLikeUtils(
            "https://quiltmc.org/",
            "https://www.mcmod.cn/class/3901.html",
            "https://meta.quiltmc.org/v3",
            "quilt",
            Addon.QUILT
        )

        private const val INSTALLER_METADATA_URL = "%s/versions/installer"
        private const val LOADER_METADATA_URL = "%s/versions/loader"
        private const val GAME_METADATA_URL = "%s/versions/game"

        private const val JSON_DOWNLOAD_URL = "%s/versions/loader/%s/%s/profile/json"

        @Throws(JSONException::class)
        private fun deserializeLoaderVersions(input: String): Array<FabricVersion> {
            val jsonArray = JSONArray(input)
            val fabricVersions: MutableList<FabricVersion> = ArrayList()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)

                val fabricVersion = FabricVersion()
                if (jsonObject.has("stable")) fabricVersion.stable = jsonObject.getBoolean("stable")
                fabricVersion.version = jsonObject.getString("version")

                fabricVersions.add(fabricVersion)
            }
            return fabricVersions.toTypedArray()
        }

        @Throws(DownloadUtils.ParseException::class)
        private fun deserializeRawVersions(jsonArrayIn: String): Array<FabricVersion> {
            try {
                return Tools.GLOBAL_GSON.fromJson(jsonArrayIn, Array<FabricVersion>::class.java)
            } catch (e: JsonSyntaxException) {
                e(FabricLikeUtils::class.java.name, Tools.printToString(e))
                throw DownloadUtils.ParseException(null)
            }
        }
    }
}
