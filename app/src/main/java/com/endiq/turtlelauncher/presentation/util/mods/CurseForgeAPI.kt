package com.endiq.turtlelauncher.presentation.util.mods

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.data.mods.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class CurseForgeAPI {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val baseUrl = "https://api.curseforge.com/v1"
    private val apiKey = BuildConfig.CURSEFORGE_API_KEY

    // Minecraft Java Edition game ID
    private val MINECRAFT_GAME_ID = 432
    // Modpacks class ID
    private val MODPACKS_CLASS_ID = 4471

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()
    }

    // 모드팩 검색
    fun searchModpacks(
        query: String = "",
        gameVersion: String = "",
        pageSize: Int = 20,
        index: Int = 0
    ): List<CurseForgeMod> {
        val url = buildString {
            append("$baseUrl/mods/search?gameId=$MINECRAFT_GAME_ID")
            append("&classId=$MODPACKS_CLASS_ID")
            append("&pageSize=$pageSize")
            append("&index=$index")
            append("&sortField=2")  // popularity
            append("&sortOrder=desc")
            if (query.isNotEmpty()) append("&searchFilter=${query}")
            if (gameVersion.isNotEmpty()) append("&gameVersion=$gameVersion")
        }

        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: return emptyList()
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeMod>>() {}.type
            return gson.fromJson<CurseForgeListResponse<CurseForgeMod>>(json, type).data
        }
    }

    // 모드팩 파일 목록
    fun getModFiles(modId: Int, gameVersion: String = ""): List<CurseForgeFile> {
        val url = buildString {
            append("$baseUrl/mods/$modId/files?pageSize=10")
            if (gameVersion.isNotEmpty()) append("&gameVersion=$gameVersion")
        }

        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: return emptyList()
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>() {}.type
            Log.d("TURTLE_LAUNCHER", "download-url 응답 코드: ${response.code}")
            Log.d("TURTLE_LAUNCHER", "download-url 응답 body: $json")

            return gson.fromJson<CurseForgeListResponse<CurseForgeFile>>(json, type).data
        }
    }

    fun getFileDownloadUrl(modId: Int, fileId: Int): String? {
        val url = "$baseUrl/mods/$modId/files/$fileId/download-url"
        val request = buildRequest(url)
        return try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: return null
                val dataElement = JsonParser.parseString(json)
                    .asJsonObject["data"]
                if (dataElement != null && !dataElement.isJsonNull) {
                    dataElement.asString
                } else {
                    // downloadUrl이 null인 경우 파일명으로 CDN URL 구성
                    buildCdnUrl(modId, fileId)
                }
            }
        } catch (e: Exception) {
            buildCdnUrl(modId, fileId)
        }
    }

    private fun buildCdnUrl(modId: Int, fileId: Int): String? {
        // 파일 정보에서 fileName 가져오기
        val url = "$baseUrl/mods/$modId/files/$fileId"
        val request = buildRequest(url)
        return try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: return null
                val data = JsonParser.parseString(json)
                    .asJsonObject["data"].asJsonObject
                val fileName = data["fileName"].asString
                val part1 = fileId / 1000
                val part2 = fileId % 1000
                "https://edge.forgecdn.net/files/$part1/$part2/$fileName"
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "CDN URL 구성 실패: ${e.message}")
            null
        }
    }

    private fun getFileDirectUrl(modId: Int, fileId: Int): String? {
        // CurseForge CDN 직접 URL 패턴
        val url = "$baseUrl/mods/$modId/files/$fileId"
        val request = buildRequest(url)
        return try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: return null
                val type = object : TypeToken<CurseForgeResponse<CurseForgeFile>>() {}.type
                val resp = gson.fromJson<CurseForgeResponse<CurseForgeFile>>(json, type)
                resp?.data?.downloadUrl
            }
        } catch (_: Exception) { null }
    }

    // 여러 파일 정보 한번에 가져오기
    fun getFiles(fileIds: List<Int>): List<CurseForgeFile> {
        if (fileIds.isEmpty()) return emptyList()
        val url = "$baseUrl/mods/files"
        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            gson.toJson(mapOf("fileIds" to fileIds))
        )
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: return emptyList()
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>() {}.type
            return gson.fromJson<CurseForgeListResponse<CurseForgeFile>>(json, type).data
        }
    }

    /**
     * 여러 프로젝트(모드/리소스팩/셰이더팩 등)의 메타를 한 번에 가져온다.
     * 모드팩 manifest.files[] 는 종류 구분이 없어서(그냥 projectID/fileID 쌍), 각 프로젝트의
     * classId(6=Mods, 12=Resource Packs, 6552=Shader Packs 등)를 알아내 설치 경로를
     * 올바르게 나누는 데 쓴다.
     */
    fun getMods(modIds: List<Int>): List<CurseForgeMod> {
        if (modIds.isEmpty()) return emptyList()
        val url = "$baseUrl/mods"
        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            gson.toJson(mapOf("modIds" to modIds))
        )
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: return emptyList()
                val type = object : TypeToken<CurseForgeListResponse<CurseForgeMod>>() {}.type
                gson.fromJson<CurseForgeListResponse<CurseForgeMod>>(json, type)?.data ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "getMods 실패: ${e.message}")
            emptyList()
        }
    }
}