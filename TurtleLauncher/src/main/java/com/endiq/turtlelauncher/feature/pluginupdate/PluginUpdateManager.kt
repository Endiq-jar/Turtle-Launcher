package com.endiq.turtlelauncher.feature.pluginupdate

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.endiq.turtlelauncher.R
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
import net.endiq.launcher.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks TurtleLauncher's real upstream renderer/driver plugin sources
 * directly — no custom manifest of our own — and offers to download +
 * install whichever assets are newer than what's currently installed.
 *
 *  - Renderer plugins (ANGLE/LTW, GL4ES, Mesa driver builds): github.com/ShirosakiMio/FCLRendererPlugin (release tag "Renderer")
 *  - MobileGlues (official upstream, not the older FCL-bundled copy): github.com/MobileGL-Dev/MobileGlues-release (latest release)
 *  - Krypton Wrapper / NG-GL4ES: github.com/BZLZHH/NGG-FCLRendererPlugin (latest release)
 *  - Vulkan driver plugins (Turnip etc.): github.com/FCL-Team/FCLDriverPlugin (release tag "Turnip")
 *
 * FCLDriverPlugin publishes its builds as .zip assets attached to one rolling release
 * tag, which RendererPluginManager/DriverPluginManager's local-plugin importer can
 * extract directly. The three renderer sources above, however, actually distribute
 * *.apk* files — each one a small standalone companion app that installs itself and
 * that TurtleLauncher then detects via PackageManager (see
 * RendererPluginManager.parseApkPlugin) — not zips. Installing one of those means
 * downloading it and handing it to the real Android package installer, the same way
 * TurtleLauncher's own self-update does (see [UpdateUtils.installApk]), not extracting
 * it as a local plugin. Note there's no dedicated "VirGL" asset anywhere upstream —
 * on Android that support rides on a Mesa driver build having the virpipe/virgl gallium
 * driver compiled in plus a running vtest server process, not a standalone plugin, so
 * it isn't listed as its own catalog entry here.
 */
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

    /**
     * Checks both upstream sources and reports back any assets that differ
     * from what's currently installed. Safe to call from a button click —
     * respects a 5-minute cooldown unless [force] is set (e.g. from a
     * manual "Check now" tap), exactly like the launcher's own self-update
     * check.
     */
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

    /**
     * Downloads one asset and installs it. A .zip is imported as a local plugin and usable
     * immediately (no restart, no extra user step). A .apk is a standalone companion app —
     * it can only be installed through the real Android package installer, so this hands it
     * to [UpdateUtils.installApk] (same flow as TurtleLauncher's own self-update) and returns
     * once that confirmation dialog is showing; the actual install happens after the user
     * taps confirm, so the downloaded file is deliberately NOT deleted here.
     */
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

            // TurtleLauncher: this used to filter for ".zip" only. That matched
            // FCLDriverPlugin's Turnip builds, but the renderer sources here (FCLRendererPlugin,
            // MobileGlues-release, NGG-FCLRendererPlugin) all publish their current releases as
            // standalone .apk companion apps instead — the .zip filter meant this returned
            // nothing for any of them.
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
