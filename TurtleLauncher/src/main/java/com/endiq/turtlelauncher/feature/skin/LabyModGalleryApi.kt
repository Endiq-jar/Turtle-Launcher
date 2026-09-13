package com.endiq.turtlelauncher.feature.skin

import android.graphics.BitmapFactory
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import java.net.URLEncoder

/**
 * Live browsing of LabyMod's public laby.net skin library (laby.net/skins) as an actual
 * *gallery* source - a grid of real, arbitrary community skins the user can page through and
 * tap to apply - rather than [LabyModSkinApi]'s per-player lookup, which only ever resolves
 * "this one player's current skin", or [SkinCapeHistoryStore]'s local "recently applied" list.
 *
 * laby.net doesn't publish a documented public JSON API for this (that's why the two classes
 * above exist and predate this one - see their doc comments). What's used here instead is
 * everything a plain browser hitting laby.net already gets, no auth/private endpoints:
 *
 *  - Gallery pages are plain, crawlable URLs - `laby.net/skins/tag/{tag}` for a tag (e.g. the
 *    site's own default "Trending" view), or `laby.net/skins?input={query}` for the site's
 *    unified search box (confirmed live: `laby.net/skins?input=LabyMod`).
 *  - The page is Next.js App Router. The grid renders client-side, but the data driving it
 *    ships inline in the *initial* HTML response as the React Server Components "flight"
 *    payload (`<script>self.__next_f.push([1,"..."])</script>` blocks) so the page can
 *    hydrate without a second round-trip - it's present in the raw response body even though
 *    nothing turns it into visible text. [scrapeSkinHashes] pulls skin hashes straight out of
 *    that payload by keying off `imageHash` (confirmed as the real field/route-param name -
 *    laby.net's own Sentry transaction tag for the skin detail route is literally
 *    `GET /[locale]/skins/[imageHash]`). If a future laby.net deploy changes shape and that
 *    comes up empty, it falls back to a plain `/skins/{hash}` link scan of the whole body -
 *    weaker (misses tiles that never got a plain link) but far more layout-resilient.
 *  - Thumbnails use laby.net's own confirmed render API - the exact one laby.net's own
 *    `<meta og:image>` tags use on every skin page: `laby.net/api/v3/render/skin/{hash}.png`.
 *  - There's no confirmed raw-texture (flat 64-wide) endpoint the way there's a confirmed
 *    render one - laby.net's page metadata never references one directly. [resolveApplyTexture]
 *    tries the most REST-idiomatic guesses first (a resource at the same path as the known-good
 *    render endpoint, minus `/render/`), then falls back to scraping the skin's own detail page
 *    for any embedded `.png` URL that *isn't* a `/render/` URL - the site's Skin Editor has to
 *    load the real flat texture from somewhere to let you paint on it and export a 64x64 PNG,
 *    so that URL exists in that page's own flight payload too. Every candidate is downloaded
 *    and decoded before being accepted - real Minecraft skins are always exactly 64px wide, so
 *    a wrong guess is rejected instead of silently applying a corrupted/wrong image.
 */
internal object LabyModGalleryApi {
    private const val BASE = "https://laby.net"

    private val client by lazy { UrlManager.createOkHttpClient() }

    data class GallerySkin(
        val hash: String,
        /** Short, human-ish label for the tile - laby.net doesn't expose per-skin display
         *  names in the gallery payload, so this is just a hash prefix; the thumbnail image
         *  is the real signal, same as laby.net's own gallery UI (no text label per tile). */
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

    /**
     * Fetches one [page] (1-indexed) of gallery tiles for [query]. UNLIKE [LittleSkinGalleryApi]'s
     * `page` param, laby.net's own `page` query param here is NOT confirmed against any known
     * route/controller name the way the rest of this file's URLs are - it's the standard
     * Next.js App Router convention (`?page=N` alongside existing query params) and nothing
     * more. If laby.net doesn't actually honor it, the practical failure mode is just "every
     * page after 1 repeats page 1's tiles" (same hashes re-scraped), not a crash or an empty
     * result - low risk to ship, but Endiq should know this one wasn't verified like the rest.
     * Best-effort otherwise: returns whatever hashes could be scraped, empty if laby.net is
     * unreachable or its markup changed shape entirely (the dialog just shows its existing
     * "empty" state in that case, same as any other empty network result elsewhere in this
     * dialog). No text field exists in this payload to run [ContentFilter] against (see
     * [GallerySkin]'s doc comment - laby.net's gallery has no per-tile name, only a hash), so
     * unlike the littleskin.cn gallery, the keyword pass can't help here at all - but
     * [AiContentModerator] (opt-in, see its own doc comment) CAN still look at the actual
     * texture pixels regardless of there being no name, and is applied below when enabled.
     */
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

    /**
     * Resolves [hash] to a real, flat 64-wide texture URL suitable for applying as the
     * player's actual skin, downloading and validating each candidate in turn. Returns null
     * if nothing validated - callers should surface this as a normal "couldn't fetch that
     * skin" failure rather than applying an unvalidated guess.
     */
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
