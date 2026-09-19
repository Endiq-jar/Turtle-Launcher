package com.endiq.turtlelauncher.feature.pluginupdate

import android.content.Context
import com.google.gson.reflect.TypeToken
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.update.UpdateUtils
import com.endiq.turtlelauncher.plugins.PluginLoader
import com.endiq.turtlelauncher.plugins.driver.DriverPluginManager
import com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors.Companion.runInUIThread
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.http.NetworkUtils
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.path.UrlManager
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object PluginUpdateManager {
    private const val TAG = "PluginUpdateManager"

    private const val GITHUB_API_RENDERER =
        "https://api.github.com/repos/ShirosakiMio/FCLRendererPlugin/releases/tags/Renderer"
    private const val GITHUB_API_MOBILEGLUES =
        "https://api.github.com/repos/MobileGL-Dev/MobileGlues-release/releases/latest"
    private const val GITHUB_API_KRYPTON_WRAPPER =
        "https://api.github.com/repos/BZLZHH/NGG-FCLRendererPlugin/releases/latest"
    private const val GITHUB_API_DRIVER =
        "https://api.github.com/repos/FCL-Team/FCLDriverPlugin/releases/tags/Turnip"

    private const val COOLDOWN_MS = 5 * 60 * 1000L // matches the launcher self-update cooldown

    private val client by lazy {
        UrlManager.createOkHttpClientBuilder().build().newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val fingerprintFile: File
        get() = File(PathManager.DIR_FILE, "plugin_update_fingerprints.json")

    @JvmStatic
    fun checkForUpdates(
        context: Context,
        force: Boolean = false,
        onResult: (updates: List<PluginAssetInfo>, error: String?) -> Unit
    ) {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            runInUIThread { onResult(emptyList(), context.getString(R.string.generic_no_network)) }
            return
        }
        if (!force && ZHTools.getCurrentTimeMillis() - AllSettings.lastPluginUpdateCheck.getValue() < COOLDOWN_MS) {
            runInUIThread { onResult(emptyList(), null) }
            return
        }
        AllSettings.lastPluginUpdateCheck.put(ZHTools.getCurrentTimeMillis()).save()

        Thread {
            val results = mutableListOf<PluginAssetInfo>()
            var error: String? = null

            runCatching {
                results += fetchReleaseAssets(GITHUB_API_RENDERER, PluginKind.RENDERER)
            }.onFailure { e ->
                Logging.e(TAG, "Renderer plugin update check failed", e)
                error = e.message ?: "Renderer source unreachable"
            }

            runCatching {
                results += fetchReleaseAssets(GITHUB_API_MOBILEGLUES, PluginKind.RENDERER)
            }.onFailure { e ->
                Logging.e(TAG, "MobileGlues update check failed", e)
                error = error ?: (e.message ?: "MobileGlues source unreachable")
            }

            runCatching {
                results += fetchReleaseAssets(GITHUB_API_KRYPTON_WRAPPER, PluginKind.RENDERER)
            }.onFailure { e ->
                Logging.e(TAG, "Krypton Wrapper update check failed", e)
                error = error ?: (e.message ?: "Krypton Wrapper source unreachable")
            }

            runCatching {
                results += fetchReleaseAssets(GITHUB_API_DRIVER, PluginKind.DRIVER)
            }.onFailure { e ->
                Logging.e(TAG, "Driver plugin update check failed", e)
                error = error ?: (e.message ?: "Driver source unreachable")
            }

            val fingerprints = loadFingerprints()
            val changed = results.filter { asset ->
                val known = fingerprints[fingerprintKey(asset.kind, asset.assetName)]
                known == null || known.size != asset.remoteSize || known.updatedAt != asset.remoteUpdatedAt
            }

            // Only surface the network error if we came back with nothing at all;
            // a partial success (e.g. renderer source down, driver source fine)
            // should still report whatever updates were found.
            val reportedError = if (results.isEmpty()) error else null

            runInUIThread { onResult(changed, reportedError) }
        }.start()
    }

    @JvmStatic
    fun downloadAndInstall(
        context: Context,
        asset: PluginAssetInfo,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        Thread {
            val extension = if (asset.isApk) "apk" else "zip"
            val tempFile = File(PathManager.DIR_APP_CACHE, "plugin_update_${System.currentTimeMillis()}.$extension")
            try {
                val request = UrlManager.createRequestBuilder(asset.downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response body")
                    tempFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                }

                if (asset.isApk) {
                    val fingerprints = loadFingerprints()
                    fingerprints[fingerprintKey(asset.kind, asset.assetName)] =
                        InstalledFingerprint(asset.remoteSize, asset.remoteUpdatedAt)
                    saveFingerprints(fingerprints)

                    UpdateUtils.installApk(context, tempFile)
                    runInUIThread { onComplete(true, "${asset.assetName} downloaded — confirm the install prompt") }
                    return@Thread
                }

                val installed = when (asset.kind) {
                    PluginKind.RENDERER -> RendererPluginManager.importLocalRendererPlugin(tempFile)
                    PluginKind.DRIVER -> DriverPluginManager.importLocalDriverPlugin(tempFile)
                }

                if (installed) {
                    val fingerprints = loadFingerprints()
                    fingerprints[fingerprintKey(asset.kind, asset.assetName)] =
                        InstalledFingerprint(asset.remoteSize, asset.remoteUpdatedAt)
                    saveFingerprints(fingerprints)

                    // Force a re-scan so the new plugin is selectable right away.
                    runCatching { PluginLoader.loadAllPlugins(context, force = true) }

                    runInUIThread { onComplete(true, "${asset.assetName} installed") }
                } else {
                    runInUIThread { onComplete(false, "Couldn't import ${asset.assetName} — not a valid plugin package") }
                }
                FileUtils.deleteQuietly(tempFile)
            } catch (e: Exception) {
                Logging.e(TAG, "Failed to download/install plugin ${asset.assetName}", e)
                runInUIThread { onComplete(false, e.message ?: "Unknown error") }
                FileUtils.deleteQuietly(tempFile)
            }
        }.start()
    }

    private fun fetchReleaseAssets(apiUrl: String, kind: PluginKind): List<PluginAssetInfo> {
        val request = UrlManager.createRequestBuilder(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bodyString = response.body?.string() ?: throw IOException("Empty response body")
            val release = Tools.GLOBAL_GSON.fromJson(bodyString, GithubRelease::class.java)
                ?: throw IOException("Malformed release JSON")

            return release.assets
                .filter { it.name.endsWith(".zip", ignoreCase = true) || it.name.endsWith(".apk", ignoreCase = true) }
                .map { asset ->
                    PluginAssetInfo(
                        kind = kind,
                        assetName = asset.name,
                        downloadUrl = asset.downloadUrl,
                        remoteSize = asset.size,
                        remoteUpdatedAt = asset.updatedAt,
                        isApk = asset.name.endsWith(".apk", ignoreCase = true)
                    )
                }
        }
    }

    private fun fingerprintKey(kind: PluginKind, assetName: String) = "$kind:$assetName"

    private fun loadFingerprints(): MutableMap<String, InstalledFingerprint> {
        return runCatching {
            if (!fingerprintFile.exists()) return mutableMapOf()
            val type = object : TypeToken<MutableMap<String, InstalledFingerprint>>() {}.type
            Tools.GLOBAL_GSON.fromJson<MutableMap<String, InstalledFingerprint>>(fingerprintFile.readText(), type)
                ?: mutableMapOf()
        }.getOrElse { mutableMapOf() }
    }

    private fun saveFingerprints(map: Map<String, InstalledFingerprint>) {
        runCatching {
            fingerprintFile.writeText(Tools.GLOBAL_GSON.toJson(map))
        }.onFailure { e -> Logging.e(TAG, "Failed to persist plugin fingerprints", e) }
    }
}
