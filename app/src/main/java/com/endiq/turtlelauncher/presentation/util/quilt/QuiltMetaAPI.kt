package com.endiq.turtlelauncher.presentation.util.quilt

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request

data class QuiltLoaderEntry(
    val loader: LoaderInfo
) {
    data class LoaderInfo(val version: String, val separator: String = ".", val build: Int = 0)
}

/**
 * Quilt Meta API(meta.quiltmc.org/v3) 클라이언트.
 * Quilt 는 Fabric 의 포크라 API 응답 구조(profile json 의 mainClass/libraries/arguments)가
 * FabricMetaAPI 와 사실상 동일하다 — 실제 응답으로 확인함
 * (mainClass=org.quiltmc.loader.impl.launch.knot.KnotClient, libraries[]에 name/url).
 */
class QuiltMetaAPI {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val base = "https://meta.quiltmc.org/v3"

    fun listLoaders(mcVersion: String): List<QuiltLoaderEntry> {
        val req = Request.Builder().url("$base/versions/loader/$mcVersion").build()
        client.newCall(req).execute().use { resp ->
            val json = resp.body?.string() ?: return emptyList()
            val type = TypeToken.getParameterized(
                List::class.java, QuiltLoaderEntry::class.java
            ).type
            return gson.fromJson(json, type)
        }
    }

    /** 인스턴스 디렉토리에 저장할 Quilt 프로필 JSON 원본 */
    fun fetchProfile(mcVersion: String, loaderVersion: String): String {
        val req = Request.Builder()
            .url("$base/versions/loader/$mcVersion/$loaderVersion/profile/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Quilt profile 실패: HTTP ${resp.code}")
            return resp.body?.string() ?: throw Exception("Quilt profile 응답 비어있음")
        }
    }
}
