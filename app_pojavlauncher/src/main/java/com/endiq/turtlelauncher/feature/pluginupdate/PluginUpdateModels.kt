package com.endiq.turtlelauncher.feature.pluginupdate

import com.google.gson.annotations.SerializedName

/**
 * Minimal subset of the GitHub Releases API response (GET
 * /repos/{owner}/{repo}/releases/tags/{tag}) that we actually need.
 */
data class GithubRelease(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("published_at") val publishedAt: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList()
)

data class GithubReleaseAsset(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    val size: Long = 0L,
    @SerializedName("updated_at") val updatedAt: String? = null
)

enum class PluginKind {
    RENDERER, DRIVER
}

/** One installable asset (a single renderer or driver build) found upstream. */
data class PluginAssetInfo(
    val kind: PluginKind,
    val assetName: String,
    val downloadUrl: String,
    val remoteSize: Long,
    val remoteUpdatedAt: String?,
    /** True for a standalone companion-app plugin (.apk) that needs a real OS install via
     *  the package installer, as opposed to a .zip that [RendererPluginManager] /
     *  [com.endiq.turtlelauncher.plugins.driver.DriverPluginManager] can import directly. */
    val isApk: Boolean = false
)

/** Fingerprint of an asset we've already installed, used to detect upstream changes. */
data class InstalledFingerprint(
    val size: Long,
    val updatedAt: String?
)
