package kr.co.donghyun.flamelauncher.presentation.util.mods

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kr.co.donghyun.flamelauncher.data.mods.ModrinthVersion
import kr.co.donghyun.flamelauncher.data.mods.ModrinthProject
import kr.co.donghyun.flamelauncher.data.mods.ModrinthSearchHit
import kr.co.donghyun.flamelauncher.data.mods.ModrinthSearchResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Modrinth API v2 클라이언트.
 *
 * CurseForgeAPI 와 동일한 패턴(OkHttp + Gson, 동기 호출 → 호출부에서 IO 디스패치).
 *
 * 주의: Modrinth 는 식별 가능한 User-Agent 를 요구하며, okhttp 같은 일반 UA 는 차단될 수 있다.
 *       (형식: github_user/project/version (contact))
 */
class ModrinthAPI {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val baseUrl = "https://api.modrinth.com/v2"

    // TODO: 연락처/리포 주소를 실제 값으로 바꾸면 차단 위험이 더 낮아진다.
    private val userAgent = "donghyun/TurtleLauncher/1.0 (kr.co.donghyun.flamelauncher)"

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

    /**
     * project_type facet 값. TurtleLauncher 의 ContentType 과 매핑해서 넘긴다.
     *   modpack / mod / resourcepack / shader / datapack
     */
    fun search(
        query: String = "",
        projectType: String,           // facet: project_type
        gameVersion: String = "",      // facet: versions (비면 미적용)
        loader: String = "",           // facet: categories (모드로더. 비면 미적용)
        limit: Int = 20,
        offset: Int = 0,
    ): List<ModrinthSearchHit> {
        // facets: [["project_type:modpack"],["versions:1.20.1"],["categories:fabric"]] (AND 로 묶임)
        val facetGroups = mutableListOf<String>()
        facetGroups.add("[\"project_type:$projectType\"]")
        if (gameVersion.isNotBlank()) facetGroups.add("[\"versions:$gameVersion\"]")
        if (loader.isNotBlank()) facetGroups.add("[\"categories:${loader.lowercase()}\"]")
        val facets = "[" + facetGroups.joinToString(",") + "]"

        val url = buildString {
            append("$baseUrl/search")
            append("?limit=$limit")
            append("&offset=$offset")
            append("&index=downloads")     // 인기순
            append("&facets=").append(enc(facets))
            if (query.isNotBlank()) append("&query=").append(enc(query))
        }

        return try {
            client.newCall(buildRequest(url)).execute().use { resp ->
                val json = resp.body?.string() ?: return emptyList()
                if (!resp.isSuccessful) {
                    Log.w("FLAME_LAUNCHER", "Modrinth 검색 실패 ${resp.code}: $json")
                    return emptyList()
                }
                gson.fromJson(json, ModrinthSearchResponse::class.java)?.hits ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "Modrinth 검색 예외: ${e.message}")
            emptyList()
        }
    }

    /** 프로젝트 상세 (긴 설명 body, 갤러리 등) */
    fun getProject(idOrSlug: String): ModrinthProject? {
        val url = "$baseUrl/project/$idOrSlug"
        return try {
            client.newCall(buildRequest(url)).execute().use { resp ->
                val json = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                gson.fromJson(json, ModrinthProject::class.java)
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "Modrinth 프로젝트 조회 예외: ${e.message}")
            null
        }
    }

    /**
     * 프로젝트 버전 목록.
     * loaders/game_versions 로 필터 가능(비우면 전체).
     */
    fun getVersions(
        idOrSlug: String,
        loaders: List<String>? = null,
        gameVersions: List<String>? = null,
    ): List<ModrinthVersion> {
        val url = buildString {
            append("$baseUrl/project/$idOrSlug/version")
            val params = mutableListOf<String>()
            if (!loaders.isNullOrEmpty()) {
                params.add("loaders=" + enc("[" + loaders.joinToString(",") { "\"$it\"" } + "]"))
            }
            if (!gameVersions.isNullOrEmpty()) {
                params.add("game_versions=" + enc("[" + gameVersions.joinToString(",") { "\"$it\"" } + "]"))
            }
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }
        return try {
            client.newCall(buildRequest(url)).execute().use { resp ->
                val json = resp.body?.string() ?: return emptyList()
                if (!resp.isSuccessful) return emptyList()
                val type = object : TypeToken<List<ModrinthVersion>>() {}.type
                gson.fromJson<List<ModrinthVersion>>(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "Modrinth 버전 조회 예외: ${e.message}")
            emptyList()
        }
    }

    /** 단일 버전 조회 (버전 id 로) */
    fun getVersion(versionId: String): ModrinthVersion? {
        val url = "$baseUrl/version/$versionId"
        return try {
            client.newCall(buildRequest(url)).execute().use { resp ->
                val json = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                gson.fromJson(json, ModrinthVersion::class.java)
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "Modrinth 단일 버전 조회 예외: ${e.message}")
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}