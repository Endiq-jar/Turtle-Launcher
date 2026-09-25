package kr.co.donghyun.flamelauncher.data.auth

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Battly publishes a compatible authlib-injector build in a small file
 * manifest. Prefer that build for Battly accounts, but fall back to Flame's
 * bundled agent when the manifest is temporarily unavailable.
 */
object BattlyAuthlibManager {
    private const val TAG = "BattlyAuthlib"
    private const val FILES_URL = "https://api.battlylauncher.com/battlylauncher/files"
    private const val AUTHLIB_NAME = "authlib-injector.jar"

    fun ensureAuthlib(context: Context, bundled: File): File {
        val cache = File(context.filesDir, "runtime/battly-authlib-injector.jar")
        return runCatching {
            val manifest = read(FILES_URL)
            val entries = JsonParser.parseString(manifest).asJsonArray
            val entry = findAndroidAuthlib(entries)
                ?: throw IOException("Battly did not publish an Android authlib")
            val path = entry.get("path")?.asString?.takeIf { it.isNotBlank() } ?: AUTHLIB_NAME
            val destination = safeChild(cache.parentFile ?: context.filesDir, path)
            val sha1 = entry.get("hash")?.asString.orEmpty()
            val size = entry.get("size")?.asLong ?: -1L
            if (!valid(destination, sha1, size)) {
                val rawUrl = entry.get("url")?.asString?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Battly authlib URL is missing")
                val url = java.net.URI(FILES_URL).resolve(rawUrl).toString()
                download(url, destination)
            }
            if (!valid(destination, sha1, size)) {
                destination.delete()
                throw IOException("Battly authlib checksum or size verification failed")
            }
            destination
        }.onFailure {
            Log.w(TAG, "Using bundled authlib for Battly account", it)
        }.getOrElse {
            bundled
        }
    }

    private fun findAndroidAuthlib(entries: JsonArray): JsonObject? =
        entries.firstOrNull { element ->
            if (!element.isJsonObject) return@firstOrNull false
            val item = element.asJsonObject
            if (item.get("path")?.asString != AUTHLIB_NAME) return@firstOrNull false
            val compatibilities = item.getAsJsonArray("compatibilities")
            compatibilities == null || compatibilities.any {
                it.asString.equals("android", ignoreCase = true)
            }
        }?.asJsonObject

    private fun safeChild(parent: File, relative: String): File {
        val root = parent.canonicalFile
        val child = File(root, relative).canonicalFile
        require(child.path == root.path || child.path.startsWith(root.path + File.separator)) {
            "Battly authlib path escapes the cache directory"
        }
        child.parentFile?.mkdirs()
        return child
    }

    private fun read(url: String): String =
        open(url).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun download(url: String, destination: File) {
        val temporary = File(destination.parentFile, ".${destination.name}.download")
        open(url).inputStream.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
        }
        if (!temporary.renameTo(destination)) {
            destination.delete()
            check(temporary.renameTo(destination)) { "Could not install Battly authlib" }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "FlameLauncher/2.2")
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw IOException("Battly HTTP $status")
        }
        return connection
    }

    private fun valid(file: File, sha1: String, size: Long): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (size >= 0 && file.length() != size) return false
        if (sha1.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(sha1, true)
    }
}
