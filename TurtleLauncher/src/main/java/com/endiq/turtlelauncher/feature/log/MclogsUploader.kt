package com.endiq.turtlelauncher.feature.log

import com.google.gson.JsonParser
import com.endiq.turtlelauncher.utils.path.UrlManager
import okhttp3.FormBody

/**
 * Small, self-contained client for the mclo.gs log-hosting API (https://api.mclo.gs/), used by
 * ShareLogsFragment's "Upload to mclo.gs" action. Mirrors [com.endiq.turtlelauncher.feature.mod.ModrinthDirectApi]'s
 * shape: a stateless object, the app's shared OkHttpClient, best-effort/never-throwing calls.
 */
object MclogsUploader {
    private const val UPLOAD_URL = "https://api.mclo.gs/1/log"
    // mclo.gs caps stored logs at 10MB; trim well below that so the request body itself
    // (plus form-encoding overhead) never risks a server-side rejection on a huge log.
    private const val MAX_CONTENT_CHARS = 8 * 1024 * 1024

    sealed class Result {
        data class Success(val url: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Uploads [logContent] and returns the resulting mclo.gs share URL, or a failure reason.
     * Blocking - call from a background thread (e.g. via [com.endiq.turtlelauncher.task.Task]).
     */
    fun upload(logContent: String): Result {
        if (logContent.isBlank()) return Result.Failure("Log is empty")
        val trimmed = if (logContent.length > MAX_CONTENT_CHARS) {
            logContent.takeLast(MAX_CONTENT_CHARS)
        } else logContent

        return runCatching {
            val body = FormBody.Builder().add("content", trimmed).build()
            val request = UrlManager.createRequestBuilder(UPLOAD_URL, body).build()
            UrlManager.createOkHttpClient().newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@use Result.Failure("Empty response (HTTP ${response.code})")
                }
                val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()
                    ?: return@use Result.Failure("Malformed response (HTTP ${response.code})")

                if (json.get("success")?.asBoolean == true) {
                    val url = json.get("url")?.asString
                    if (url.isNullOrBlank()) Result.Failure("Response had no URL")
                    else Result.Success(url)
                } else {
                    Result.Failure(json.get("error")?.asString ?: "Upload rejected (HTTP ${response.code})")
                }
            }
        }.onFailure { e ->
            Logging.e("MclogsUploader", "Upload to mclo.gs failed", e)
        }.getOrElse { e ->
            Result.Failure(e.message ?: "Network error")
        }
    }
}
