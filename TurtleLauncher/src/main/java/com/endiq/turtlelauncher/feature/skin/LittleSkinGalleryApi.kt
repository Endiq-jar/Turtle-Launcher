package com.endiq.turtlelauncher.feature.skin

import android.graphics.BitmapFactory
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import org.json.JSONObject
import java.net.URLEncoder

internal object LittleSkinGalleryApi {
    private const val BASE = "https://littleskin.cn"

    private val client by lazy { UrlManager.createOkHttpClient() }

    data class GallerySkin(
        val tid: Long,
        /** Real uploader-given name, unlike laby.net's gallery which has no per-tile name and
         *  falls back to a hash prefix - LittleSkin's `list` response includes `name` directly. */
        val label: String
    )

    /** [Trending] mirrors the site's own default "sort by likes" library view; [Search] maps
     *  onto the `keyword` query param. */
    sealed class GalleryQuery {
        object Trending : GalleryQuery()
        data class Search(val text: String) : GalleryQuery()
    }

    fun fetchGallery(mode: String, query: GalleryQuery, page: Int = 1): List<GallerySkin> = runCatching {
        val filter = if (mode == "cape") "cape" else "skin"
        val sort = if (query is GalleryQuery.Search) "time" else "likes"
        val keyword = (query as? GalleryQuery.Search)?.text.orEmpty()
        val url = "$BASE/skinlib/list?filter=$filter&sort=$sort&keyword=${encode(keyword)}&page=$page"
        val body = fetchBody(url) ?: return@runCatching emptyList()
        parseListResponse(body).filter { skin ->
            AiContentModerator.fetchAndCheck("littleskin:${skin.tid}", thumbnailUrl(skin.tid)) != false
        }
    }.onFailure { e -> Logging.e("LittleSkinGalleryApi", "Gallery fetch failed for $query page $page", e) }.getOrDefault(emptyList())

    /** Confirmed render endpoint - safe to use directly, no guessing involved. */
    fun thumbnailUrl(tid: Long, heightPx: Int = 160): String = "$BASE/preview/$tid?height=$heightPx"

    /**
     * Resolves [tid] to a real, flat texture URL suitable for applying, downloading and
     * validating the result before returning it. Returns null if either request fails or the
     * downloaded bytes don't decode as a real Minecraft skin/cape texture - callers should
     * surface this as a normal "couldn't fetch that skin" failure, same as [LabyModGalleryApi].
     */
    fun resolveApplyTexture(tid: Long): ByteArray? {
        val infoBody = fetchBody("$BASE/skinlib/info/$tid") ?: return null
        val hash = runCatching { JSONObject(infoBody).getString("hash") }
            .onFailure { e -> Logging.e("LittleSkinGalleryApi", "No hash in info response for tid=$tid", e) }
            .getOrNull() ?: return null
        return validatedSkinBytes("$BASE/textures/$hash")
    }

    private fun parseListResponse(body: String): List<GallerySkin> = runCatching {
        val data = JSONObject(body).getJSONArray("data")
        (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val tid = item.optLong("tid", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            val rawName = item.optString("name").takeIf { it.isNotBlank() } ?: "#$tid"
            if (ContentFilter.isBlockedName(rawName)) return@mapNotNull null
            GallerySkin(tid, ContentFilter.toDisplayLabel(rawName, tid.toString()))
        }
    }.onFailure { e -> Logging.e("LittleSkinGalleryApi", "Failed to parse skinlib/list response", e) }
        .getOrDefault(emptyList())

    /** Same 64px-wide validation [LabyModGalleryApi] uses - rejects anything that isn't
     *  actually shaped like a real Minecraft skin/cape texture. */
    private fun validatedSkinBytes(url: String): ByteArray? = runCatching {
        val request = UrlManager.createRequestBuilder(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val isValidTexture = options.outWidth == 64 && (options.outHeight == 64 || options.outHeight == 32)
            if (isValidTexture) bytes else null
        }
    }.onFailure { e -> Logging.e("LittleSkinGalleryApi", "Candidate check failed for $url", e) }.getOrNull()

    private fun fetchBody(url: String): String? = runCatching {
        val request = UrlManager.createRequestBuilder(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.onFailure { e -> Logging.e("LittleSkinGalleryApi", "Request failed for $url", e) }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
