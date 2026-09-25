package com.endiq.turtlelauncher.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.data.mods.ContentItem
import com.endiq.turtlelauncher.data.mods.CurseForgeListResponse
import com.endiq.turtlelauncher.data.mods.CurseForgeMod
import com.endiq.turtlelauncher.domain.repository.ContentRepository
import com.endiq.turtlelauncher.presentation.util.minecraft.VersionRepository
import com.endiq.turtlelauncher.presentation.util.mods.ModrinthAPI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContentRepository 구현체 — ContentPackBrowserActivity 에 있던 검색/필터/설치여부 로직을
 * 그대로 이관했다(재작성 아님, 어댑터 방식 — 회귀버그 위험 최소화).
 */
@Singleton
class ContentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val versionRepository: VersionRepository,
) : ContentRepository {

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private val modrinthApi = ModrinthAPI()

    override suspend fun searchCurseForge(
        query: String,
        classId: Int,
        gameVersion: String,
        modLoaderType: Int?,
        index: Int,
        pageSize: Int,
    ): List<ContentItem> {
        val urlBuilder = StringBuilder("https://api.curseforge.com/v1/mods/search")
            .append("?gameId=432")
            .append("&classId=").append(classId)
            .append("&index=").append(index)
            .append("&pageSize=").append(pageSize)
            .append("&sortField=2")    // Popularity
            .append("&sortOrder=desc")

        if (query.isNotBlank()) urlBuilder.append("&searchFilter=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        if (gameVersion.isNotBlank()) urlBuilder.append("&gameVersion=").append(gameVersion)
        if (modLoaderType != null) urlBuilder.append("&modLoaderType=").append(modLoaderType)

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()

        val mods: List<CurseForgeMod> = httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e("TURTLE_LAUNCHER", "CurseForge 검색 HTTP ${response.code}: ${body.take(300)}")
                throw IOException("CurseForge API HTTP ${response.code}")
            }
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeMod>>() {}.type
            gson.fromJson<CurseForgeListResponse<CurseForgeMod>>(body, type)?.data ?: emptyList()
        }
        return mods.map { ContentItem.from(it) }
    }

    override suspend fun searchModrinth(
        query: String,
        projectType: String,
        gameVersion: String,
        loader: String,
        offset: Int,
        limit: Int,
    ): List<ContentItem> {
        val hits = modrinthApi.search(
            query = query,
            projectType = projectType,
            gameVersion = gameVersion,
            loader = loader,
            limit = limit,
            offset = offset,
        )
        return hits.map { ContentItem.from(it) }
    }

    override suspend fun getAvailableMcVersions(): List<String> =
        versionRepository.fetchVersionList()
            .filter { it.type == "release" }
            .map { it.id }

    override fun getInstalledContentKeys(contentPacks: List<ContentItem>): Set<String> {
        val instances = InstanceManager.listInstances(context)
        val installedCfKeys = instances.mapNotNull { it.sourceModId }.map { "cf:$it" }.toSet()
        val installedNames = instances.filter { it.sourceModId == null }.map { it.name }.toSet()
        return contentPacks
            .filter { it.trackKey in installedCfKeys || it.name in installedNames }
            .map { it.trackKey }
            .toSet()
    }
}
