package com.endiq.turtlelauncher.feature.skin

import android.graphics.BitmapFactory
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import java.net.URLEncoder

internal object LabyModGalleryApi {
    private const val BASE = "https://laby.net"

    private val client by lazy { UrlManager.createOkHttpClient() }

    data class GallerySkin(
        val hash: String,
        val label: String = "#" + hash.take(6)
    ) {
        fun detailPageUrl(): String = "$BASE/skins/$hash"
    }

    /** Where a gallery fetch should look. [Trending] mirrors laby.net's own default "Skins"
     *  tab; [Tag] and [Search] map directly onto the two confirmed live URL forms. */
    sealed class GalleryQuery {
        object Trending : GalleryQuery()
        data class Tag(val tag: String) : GalleryQuery()
        data class Search(val text: String) : GalleryQuery()
    }

    private val HASH_NEAR_KEY_REGEX = Regex("imageHash[^0-9a-f]{1,8}([0-9a-f]{32})(?![0-9a-f])")
    private val HASH_HREF_REGEX = Regex("/skins/([0-9a-f]{32})(?![0-9a-f])")
    private val PNG_URL_REGEX = Regex("""https?://[^"'\s\\]+?\.png""")

    fun fetchGallery(query: GalleryQuery, page: Int = 1): List<GallerySkin> = runCatching {
        val url = when (query) {
            is GalleryQuery.Trending -> "$BASE/skins/tag/Trending?page=$page"
            is GalleryQuery.Tag -> "$BASE/skins/tag/${encode(query.tag)}?page=$page"
            is GalleryQuery.Search -> "$BASE/skins?input=${encode(query.text)}&page=$page"
        }
        val html = fetchBody(url) ?: return@runCatching emptyList()
        scrapeSkinHashes(html).map { GallerySkin(it) }.filter { skin ->
            AiContentModerator.fetchAndCheck("laby:${skin.hash}", thumbnailUrl(skin.hash)) != false
        }
    }.onFailure { e -> Logging.e("LabyModGalleryApi", "Gallery fetch failed for $query page $page", e) }.getOrDefault(emptyList())

    /** Confirmed render endpoint - safe to use directly, no guessing involved. */
    fun thumbnailUrl(hash: String, sizePx: Int = 160): String =
        "$BASE/api/v3/render/skin/$hash.png?height=$sizePx&width=$sizePx"

    fun resolveApplyTexture(hash: String): ByteArray? {
        val directCandidates = listOf(
            "$BASE/api/v3/skin/$hash.png",
            "https://skin.laby.net/api/skin/$hash.png"
        )
        for (candidate in directCandidates) {
            validatedSkinBytes(candidate)?.let { return it }
        }

        // Fall back to scraping the skin's own detail page for an embedded non-render .png URL.
        val detailHtml = fetchBody("$BASE/skins/$hash") ?: return null
        val pngUrls = PNG_URL_REGEX.findAll(detailHtml)
            .map { it.value.replace("\\/", "/") }
            .filterNot { it.contains("/render/") }
            .distinct()
        for (candidate in pngUrls) {
            validatedSkinBytes(candidate)?.let { return it }
        }
        return null
    }

    /** Downloads [url] and returns the bytes only if they decode as a real Minecraft skin
     *  texture (always exactly 64px wide; 32 or 64px tall). Rejects everything else, including
     *  a wrong-guessed candidate that happens to resolve to some other, differently-shaped
     *  image (e.g. a posed render). */
    private fun validatedSkinBytes(url: String): ByteArray? = runCatching {
        val request = UrlManager.createRequestBuilder(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val isValidSkin = options.outWidth == 64 && (options.outHeight == 64 || options.outHeight == 32)
            if (isValidSkin) bytes else null
        }
    }.onFailure { e -> Logging.e("LabyModGalleryApi", "Candidate check failed for $url", e) }.getOrNull()

    private fun scrapeSkinHashes(html: String): List<String> {
        val primary = LinkedHashSet<String>()
        HASH_NEAR_KEY_REGEX.findAll(html).forEach { primary += it.groupValues[1] }
        if (primary.size >= 6) return primary.take(60).toList()

        // Sparse/empty primary match - broaden the net with plain href scanning too.
        val combined = LinkedHashSet<String>(primary)
        HASH_HREF_REGEX.findAll(html).forEach { combined += it.groupValues[1] }
        return combined.take(60).toList()
    }

    private fun fetchBody(url: String): String? = runCatching {
        val request = UrlManager.createRequestBuilder(url)
            .header("Accept", "text/html")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.onFailure { e -> Logging.e("LabyModGalleryApi", "Page fetch failed for $url", e) }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
