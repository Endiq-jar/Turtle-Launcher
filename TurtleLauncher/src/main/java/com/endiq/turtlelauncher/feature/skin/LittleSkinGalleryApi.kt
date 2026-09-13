package com.endiq.turtlelauncher.feature.skin

import android.graphics.BitmapFactory
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Live browsing of littleskin.cn's public skin library (littleskin.cn/skinlib) as an actual
 * *gallery* source, the same shape as [LabyModGalleryApi] but for LittleSkin - the largest
 * Minecraft skin-hosting / third-party Yggdrasil auth site in Mainland China (Blessing Skin
 * Server under the hood, per LittleSkin's own manual). Unlike laby.net, LittleSkin natively
 * supports browsing *capes* too (`filter=cape`), not just skins.
 *
 * littleskin.cn doesn't document a public browsing API either (its manual at
 * manual.littlesk.in/advanced/api only documents auth-gated Yggdrasil/OAuth endpoints, nothing
 * about the skin library grid). What's used here instead was confirmed by reading the actual
 * source of bs-community/blessing-skin-server (github.com/bs-community/blessing-skin-server) -
 * the open-source project LittleSkin's own manual says it runs ("深度定制的 Blessing Skin
 * Server", a customized fork) - since these specific routes are core, ecosystem-relied-upon
 * functionality (the raw texture route is even part of the CustomSkinLoader-compatible
 * default load list LittleSkin advertises), not obscure internals likely to have been
 * stripped out by their customizations:
 *
 *  - `GET /skinlib/list?filter={skin|cape}&sort={likes|time}&keyword={q}&page={n}` -
 *    `SkinlibController::library()` in the upstream source. No auth needed for public
 *    textures (anonymous requests are server-side filtered to `public=true` already, so
 *    nothing private ever appears here). Returns a Laravel `paginate()` envelope -
 *    `{"data": [{"tid": ..., "name": ..., "type": ..., "likes": ..., "nickname": ...}, ...],
 *    "current_page": ..., "last_page": ...}` - `type` is `steve`/`alex` for skins (classic vs
 *    slim model) or `cape` for capes; `filter=skin` matches both skin types server-side.
 *  - `GET /preview/{tid}?height={px}` - `TextureController::preview()`. A server-rendered PNG
 *    (isometric skin render, or a flat cape render) keyed by texture id - this project's
 *    thumbnail endpoint, same role as laby.net's `/api/v3/render/skin/{hash}.png`.
 *  - Applying a tile needs the real flat texture, which the list/preview endpoints don't
 *    expose directly (`list` has no `hash` field, `preview` is a re-rendered isometric PNG,
 *    not the flat original). [resolveApplyTexture] does what upstream's own web UI effectively
 *    does in two requests: `GET /skinlib/info/{tid}` (`SkinlibController::info()`, returns the
 *    full `Texture` model as JSON, including `hash`) to resolve the hash, then
 *    `GET /textures/{hash}` (`TextureController::texture()`, no auth gate at all in the
 *    upstream source) for the actual flat bytes. Every result is downloaded and decoded before
 *    being accepted - same validation discipline as [LabyModGalleryApi.resolveApplyTexture].
 *
 * None of the above was hit live against littleskin.cn itself from this environment (no
 * network path to it here) - it's confirmed against the real upstream source code LittleSkin
 * states it runs, not guessed. If LittleSkin's customizations did move/rename any of these,
 * the failure mode is just an empty gallery / a normal "couldn't fetch" apply error, same as
 * any other best-effort network source in this dialog - nothing this file does is destructive.
 */
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

    /**
     * Fetches one [page] (1-indexed, matching the real `page` param in Blessing Skin's
     * `SkinlibController::library()`) of gallery tiles for [query]. [mode] is "skin" or "cape"
     * (same string [SkinCapeDialog] already uses elsewhere) and maps directly onto the `filter`
     * param. Names that trip [ContentFilter.isBlockedName] are dropped while parsing - see
     * that object's doc comment for what it does and doesn't catch - and surviving names are
     * normalized via [ContentFilter.toDisplayLabel]. If [AiContentModerator] is enabled
     * (opt-in, see its own doc comment), each surviving tile's actual texture is also run
     * through it and dropped on a confirmed "inappropriate" verdict - a real pixel-level
     * check the keyword pass above can't do, at the cost of one extra request+API call per
     * tile, only when the user has turned that on.
     * Best-effort: returns whatever could be parsed, empty if littleskin.cn is unreachable or
     * its response shape changed - the dialog just shows its existing "empty" state.
     */
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
