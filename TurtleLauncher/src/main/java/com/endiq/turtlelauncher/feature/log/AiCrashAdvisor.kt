package com.endiq.turtlelauncher.feature.log

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.UrlManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object AiCrashAdvisor {

    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MAX_LOG_CHARS = 6000

    private const val SYSTEM_PROMPT =
        "You are a crash-diagnosis assistant built into an Android Minecraft launcher called " +
        "TurtleLauncher (a TurtleLauncher fork). You are given the tail of a " +
        "crash log. Reply with a short, concrete, numbered list of the most likely fix steps a " +
        "non-developer player can try themselves, most-likely-to-help first. Be specific about what " +
        "in the log points to the cause. If you genuinely can't tell, say that plainly instead of " +
        "guessing. Keep the whole reply under 200 words and do not repeat the raw log back."

    /**
     * Returns a short AI-generated fix suggestion for [logText], or null if AI crash help is
     * disabled, no API key is configured, or the request fails for any reason. Blocking —
     * only call from a background thread (this mirrors [CrashAnalyzer.analyzeGameExit], which
     * already runs off the main thread as part of the JVM-exit handling path).
     */
    @JvmStatic
    fun getSuggestion(logText: String): String? {
        if (!runCatching { AllSettings.aiCrashHelpEnabled.getValue() }.getOrDefault(false)) return null
        val apiKey = runCatching { AllSettings.aiApiKey.getValue() }.getOrDefault("").trim()
        if (apiKey.isEmpty()) return null
        if (logText.isBlank()) return null

        val model = runCatching { AllSettings.aiModel.getValue() }.getOrDefault("gpt-4o-mini")
            .ifBlank { "gpt-4o-mini" }
        val trimmedLog = logText.takeLast(MAX_LOG_CHARS)

        return runCatching {
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", SYSTEM_PROMPT)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "Crash log tail:\n\n$trimmedLog")
                })
            }
            val requestBody = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                addProperty("temperature", 0.2)
                addProperty("max_tokens", 400)
            }

            val body = requestBody.toString().toRequestBody("application/json".toMediaType())
            val request = UrlManager.createRequestBuilder(ENDPOINT, body)
                .header("Authorization", "Bearer $apiKey")
                .build()

            // Independent short-timeout client — this is an interactive one-shot call during
            // crash reporting, not a bulk download; we don't want it to hang the flow for long.
            val client = UrlManager.createOkHttpClientBuilder { it.callTimeout(20, java.util.concurrent.TimeUnit.SECONDS) }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logging.w("AiCrashAdvisor", "OpenAI request failed: HTTP ${response.code}")
                    return@runCatching null
                }
                val responseBody = response.body?.string() ?: return@runCatching null
                val json = JsonParser.parseString(responseBody).asJsonObject
                json.getAsJsonArray("choices")
                    ?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                    ?.trim()
            }
        }.onFailure { e -> Logging.w("AiCrashAdvisor", "AI crash suggestion failed", e) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
