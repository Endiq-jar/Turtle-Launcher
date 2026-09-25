package kr.co.donghyun.flamelauncher.presentation.util.fabric

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request

data class FabricLoaderEntry(
    val loader: LoaderInfo,
    val intermediary: IntermediaryInfo
) {
    data class LoaderInfo(val version: String, val stable: Boolean, val build: Int = 0)
    data class IntermediaryInfo(val version: String, val stable: Boolean = true)
}

class FabricMetaAPI {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val base = "https://meta.fabricmc.net/v2"

    fun listLoaders(mcVersion: String): List<FabricLoaderEntry> {
        val req = Request.Builder().url("$base/versions/loader/$mcVersion").build()
        client.newCall(req).execute().use { resp ->
            val json = resp.body?.string() ?: return emptyList()
            val type = TypeToken.getParameterized(
                List::class.java, FabricLoaderEntry::class.java
            ).type
            return gson.fromJson(json, type)
        }
    }

    /** 인스턴스 디렉토리에 저장할 Fabric 프로필 JSON 원본 */
    fun fetchProfile(mcVersion: String, loaderVersion: String): String {
        val req = Request.Builder()
            .url("$base/versions/loader/$mcVersion/$loaderVersion/profile/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Fabric profile 실패: HTTP ${resp.code}")
            return resp.body?.string() ?: throw Exception("Fabric profile 응답 비어있음")
        }
    }
}