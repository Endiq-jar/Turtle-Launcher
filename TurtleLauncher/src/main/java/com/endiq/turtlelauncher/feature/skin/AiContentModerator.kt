package com.endiq.turtlelauncher.feature.skin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.UrlManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Optional AI pass over [LabyModGalleryApi]/[LittleSkinGalleryApi] gallery thumbnails, on top
 * of (not instead of) [ContentFilter]'s keyword check - this is the piece that can actually
 * look at pixels, which [ContentFilter]'s own doc comment says plainly it can't do.
 *
 * Opt-in and best-effort, same shape as [com.endiq.turtlelauncher.feature.log.AiCrashAdvisor]:
 *  - Disabled unless the user turns on Settings → "AI skin/cape filter"
 *    ([AllSettings.aiSkinFilterEnabled]) AND supplies their own API key
 *    ([AllSettings.aiApiKey]) - no key is bundled (see AiCrashAdvisor's doc comment for why).
 *    This is a SEPARATE opt-in from AI crash help: turning this on sends actual texture
 *    images, not just text, to whichever endpoint [AllSettings.aiModel] points at, and every
 *    classification is a real per-image API call against the user's own key/quota.
 *  - "Appropriate" here means only what a vision model looking at a 64×64-ish skin/cape
 *    texture can judge - overtly sexual, violent/gory, or hate-symbol imagery. It is not a
 *    guarantee, and a model can misjudge or miss things a human moderator wouldn't.
 *  - Fails OPEN: any failure (disabled, no key, network error, bad/unparsable response,
 *    timeout) returns null, and callers treat null as "don't block on this" - same as
 *    [ContentFilter], nothing here should make a gallery page look empty/broken just because
 *    the AI call didn't work. [ContentFilter]'s own keyword pass still applies regardless.
 *  - Per-image results are cached in memory for this process's lifetime (capped, oldest
 *    evicted first) so re-visiting a page already seen this session doesn't re-pay for
 *    classifying the same tile twice.
 */
internal object AiContentModerator {

    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MAX_CACHE_ENTRIES = 500

    private const val PROMPT =
        "You are an image content moderator for a Minecraft launcher's skin/cape gallery. " +
        "You will be shown a small Minecraft skin or cape texture image (a flat UV-mapped " +
        "pixel-art template, not a rendered 3D character). Judge only whether the texture " +
        "itself depicts anything overtly sexual, graphically violent/gory, or a hate symbol. " +
        "Ignore low resolution, odd proportions, or empty/transparent regions - those are " +
        "normal for this format. Reply with ONLY a JSON object, no other text: " +
        "{\"appropriate\": true or false}."

    private val cache = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private val client by lazy {
        UrlManager.createOkHttpClientBuilder { it.callTimeout(30, TimeUnit.SECONDS) }.build()
    }

    /**
     * Downloads [imageUrl] and classifies it, using [cacheKey] (e.g. "littleskin:123" or
     * "laby:<hash>") to dedupe repeat lookups within this process's lifetime. Returns null -
     * "unknown, don't block on this" - whenever the feature is off, unconfigured, or anything
     * about the request/response fails. Blocking - call from a background thread, same as
     * the [LabyModGalleryApi]/[LittleSkinGalleryApi] fetchGallery() functions this is meant
     * to be called from.
     */
    fun fetchAndCheck(cacheKey: String, imageUrl: String): Boolean? {
        if (!runCatching { AllSettings.aiSkinFilterEnabled.getValue() }.getOrDefault(false)) return null
        val apiKey = runCatching { AllSettings.aiApiKey.getValue() }.getOrDefault("").trim()
        if (apiKey.isEmpty()) return null

        synchronized(cache) { cache[cacheKey] }?.let { return it }

        val result = runCatching {
            val imageBytes = client.newCall(Request.Builder().url(imageUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.bytes()
            } ?: return@runCatching null

            val model = runCatching { AllSettings.aiModel.getValue() }.getOrDefault("gpt-4o-mini")
                .ifBlank { "gpt-4o-mini" }
            val base64Image = Base64.getEncoder().encodeToString(imageBytes)

            val content = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", PROMPT)
                })
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:image/png;base64,$base64Image")
                    })
                })
            }
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            }
            val requestBody = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                addProperty("temperature", 0.0)
                addProperty("max_tokens", 20)
            }

            val body = requestBody.toString().toRequestBody("application/json".toMediaType())
            val request = UrlManager.createRequestBuilder(ENDPOINT, body)
                .header("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logging.w("AiContentModerator", "Moderation request failed: HTTP ${response.code}")
                    return@runCatching null
                }
                val responseBody = response.body?.string() ?: return@runCatching null
                val replyText = JsonParser.parseString(responseBody).asJsonObject
                    .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")?.get("content")?.asString
                    ?: return@runCatching null
                // Models occasionally wrap JSON in a code fence despite instructions not to -
                // strip that rather than fail the whole classification over formatting.
                val cleaned = replyText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                JsonParser.parseString(cleaned).asJsonObject.get("appropriate")?.asBoolean
            }
        }.onFailure { e -> Logging.w("AiContentModerator", "Skin/cape moderation failed for $cacheKey", e) }
            .getOrNull()

        if (result != null) synchronized(cache) { cache[cacheKey] = result }
        return result
    }
}
