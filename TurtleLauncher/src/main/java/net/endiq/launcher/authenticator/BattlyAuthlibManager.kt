package net.endiq.launcher.authenticator

import android.util.Log
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Logger
import net.endiq.launcher.Tools
import net.endiq.launcher.utils.DownloadUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

object BattlyAuthlibManager {
    private const val TAG = "BattlyAuthlib"
    private const val FILES_URL = "https://api.battlylauncher.com/battlylauncher/files"
    private const val AUTHLIB_PATH = "authlib-injector.jar"
    const val AUTH_SERVER = "https://api.battlylauncher.com"

    @Throws(IOException::class)
    @JvmStatic
    fun ensureAuthlib(): File {
        val files: JSONArray = try {
            JSONArray(DownloadUtils.downloadString(FILES_URL))
        } catch (e: Exception) {
            // IOException or JSONException — both mean "manifest unreachable/bad"
            val cachedAuthlib = File(PathManager.DIR_GAME_HOME, AUTHLIB_PATH)
            if (cachedAuthlib.isFile) {
                Log.w(TAG, "Using cached Battly authlib because manifest could not be refreshed", e)
                return cachedAuthlib
            }
            throw IOException("Unable to load Battly files manifest", e)
        }

        var authlibEntry: JSONObject? = null
        for (i in 0 until files.length()) {
            val entry = files.optJSONObject(i) ?: continue
            if (AUTHLIB_PATH != entry.optString("path")) continue
            if (!supportsAndroid(entry.optJSONArray("compatibilities"))) continue
            authlibEntry = entry
            break
        }
        val entry = authlibEntry ?: throw IOException("Battly authlib is not available for android")

        val destination = resolveDestination(entry.optString("path", AUTHLIB_PATH))
        val sha1 = entry.optString("hash", "")
        val size = entry.optLong("size", -1)
        if (isValid(destination, sha1, size)) return destination

        val url = entry.optString("url", "")
        if (url.isEmpty()) throw IOException("Battly authlib url is empty")
        DownloadUtils.downloadFile(url, destination)
        if (!isValid(destination, sha1, size)) {
            if (!destination.delete()) {
                Log.w(TAG, "Could not delete invalid authlib: ${destination.absolutePath}")
            }
            throw IOException("Battly authlib verification failed")
        }
        return destination
    }

    /** Returns true if [baseUrl] identifies a Battly account server (used to route launch args). */
    @JvmStatic
    fun isBattlyServer(baseUrl: String?): Boolean = baseUrl != null && baseUrl.contains("battlylauncher.com")

    @JvmStatic
    fun addJvmArgumentsIfAvailable(javaArgList: MutableList<String>) {
        try {
            val authlib = ensureAuthlib()
            val authlibPath = authlib.absolutePath
            javaArgList.add("-Dbattly.api.url=$AUTH_SERVER")
            javaArgList.add("-javaagent:$authlibPath=$AUTH_SERVER")
            javaArgList.add("-Xbootclasspath/a:$authlibPath")
            Log.i(TAG, "Battly authlib attached: $authlibPath")
            Logger.appendToLog("Info: Battly authlib attached: $authlibPath")
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach Battly authlib", e)
            Logger.appendToLog("Warning: Battly authlib could not be attached: ${e.message}")
        }
    }

    private fun supportsAndroid(compatibilities: JSONArray?): Boolean {
        if (compatibilities == null) return false
        for (i in 0 until compatibilities.length()) {
            if ("android".equals(compatibilities.optString(i), ignoreCase = true)) return true
        }
        return false
    }

    @Throws(IOException::class)
    private fun resolveDestination(path: String): File {
        val base = File(PathManager.DIR_GAME_HOME).canonicalFile
        val destination = File(base, path).canonicalFile
        val basePath = base.path
        val destinationPath = destination.path
        if (destinationPath != basePath && !destinationPath.startsWith(basePath + File.separator)) {
            throw IOException("Battly authlib path escapes storage root: $path")
        }
        return destination
    }

    private fun isValid(file: File, sha1: String?, size: Long): Boolean {
        if (!file.isFile) return false
        if (!sha1.isNullOrEmpty()) return Tools.compareSHA1(file, sha1)
        if (size >= 0 && file.length() != size) return false
        return true
    }
}
