package kr.co.donghyun.flamelauncher.presentation.util.minecraft

import com.google.gson.Gson
import kr.co.donghyun.flamelauncher.data.mojang.VersionEntry
import kr.co.donghyun.flamelauncher.data.mojang.VersionManifestIndex
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class VersionRepository @Inject constructor() {
    private val client = OkHttpClient()
    private val gson = Gson()

    fun fetchVersionList(): List<VersionEntry> {
        val request = Request.Builder()
            .url("https://piston-meta.mojang.com/mc/game/version_manifest.json")
            .build()
        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("버전 목록을 읽을 수 없음")
            return gson.fromJson(json, VersionManifestIndex::class.java).versions
        }
    }

    fun fetchVersionJsonUrl(versionId: String): String {
        val versions = fetchVersionList()
        return versions.find { it.id == versionId }?.url
            ?: throw Exception("버전 $versionId 를 찾을 수 없음")
    }
}