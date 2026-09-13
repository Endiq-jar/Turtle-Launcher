package com.endiq.turtlelauncher.feature.update

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.update.LauncherVersion.FileSize
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.setting.AllSettings.Companion.ignoreUpdate
import com.endiq.turtlelauncher.task.TaskExecutors.Companion.runInUIThread
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.dialog.UpdateDialog
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.http.CallUtils
import com.endiq.turtlelauncher.utils.http.CallUtils.CallbackListener
import com.endiq.turtlelauncher.utils.http.NetworkUtils
import com.endiq.turtlelauncher.utils.path.UrlManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import okhttp3.Call
import okhttp3.Response
import org.apache.commons.io.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException


class UpdateUtils {
    companion object {
        @JvmField
        val sApkFile: File = File(PathManager.DIR_APP_CACHE, "cache.apk")
        private var LAST_UPDATE_CHECK_TIME: Long = 0

        /**
         * Update checks at startup have a 5 minute cooldown so GitHub does not rate-limit us.
         * @param force force the check (used by the in-settings update check)
         */
        @JvmStatic
        fun checkDownloadedPackage(context: Context, force: Boolean, ignore: Boolean) {
            if (force && !NetworkUtils.isNetworkAvailable(context)) {
                Toast.makeText(context, context.getString(R.string.generic_no_network), Toast.LENGTH_SHORT).show()
                return
            }

            val isRelease = (ZHTools.isRelease() || ZHTools.isPreRelease()) && !ZHTools.isDebug()

            if (sApkFile.exists()) {
                val packageManager = context.packageManager
                val packageInfo = packageManager.getPackageArchiveInfo(sApkFile.absolutePath, 0)

                if (isRelease && packageInfo != null) {
                    val packageName = packageInfo.packageName
                    val versionCode = packageInfo.versionCode
                    val thisVersionCode = ZHTools.getVersionCode()

                    if (packageName == ZHTools.getPackageName() && versionCode > thisVersionCode) {
                        installApk(context, sApkFile)
                    } else {
                        FileUtils.deleteQuietly(sApkFile)
                    }
                } else {
                    FileUtils.deleteQuietly(sApkFile)
                }
            } else {
                if (isRelease && (force || checkCooling())) {
                    AllSettings.updateCheck.put(ZHTools.getCurrentTimeMillis()).save()
                    Logging.i("Check Update", "Checking new update!")

                    // If the package is missing, fetch the update automatically.
                    updateCheckerMainProgram(context, ignore)
                }
            }
        }

        private fun checkCooling(): Boolean {
            return ZHTools.getCurrentTimeMillis() - AllSettings.updateCheck.getValue() > 5 * 60 * 1000 // 5 minute cooldown
        }

        // Real releases page, tags are vX.Y.Z.W (e.g. v1.0.0.3) - see pickLatestRelease().
        private const val RELEASES_API_URL = "https://api.github.com/repos/Endiq-jar/TurtleLauncher/releases"
        private val DOTTED_VERSION_REGEX = Regex("^\\d+(\\.\\d+)*$")

        @Synchronized
        fun updateCheckerMainProgram(context: Context, ignore: Boolean) {
            if (ZHTools.getCurrentTimeMillis() - LAST_UPDATE_CHECK_TIME <= 5000) return
            LAST_UPDATE_CHECK_TIME = ZHTools.getCurrentTimeMillis()

            CallUtils(object : CallbackListener {
                override fun onFailure(call: Call?) {
                    showFailToast(context, context.getString(R.string.update_fail))
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call?, response: Response?) {
                    if (response == null || !response.isSuccessful) {
                        showFailToast(context, context.getString(R.string.update_fail_code, response?.code ?: -1))
                        Logging.e("UpdateLauncher", "Unexpected code " + response?.code)
                        return
                    }
                    try {
                        val releases = JSONArray(response.body?.string()
                            ?: throw IOException("Empty response body"))
                        val launcherVersion = pickLatestRelease(releases)

                        if (launcherVersion == null) {
                            if (!ignore) {
                                runInUIThread {
                                    val nowVersionName = ZHTools.getVersionName()
                                    Toast.makeText(
                                        context,
                                        StringUtils.insertSpace(context.getString(R.string.update_without), nowVersionName),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            return
                        }

                        if (ignore && launcherVersion.versionName == ignoreUpdate.getValue()) return  // skip this version

                        runInUIThread {
                            UpdateDialog(context, launcherVersion).show()
                        }
                    } catch (e: Exception) {
                        Logging.e("Check Update", Tools.printToString(e))
                    }
                }
            }, RELEASES_API_URL, null).enqueue()
        }

        /**
         * Walks Endiq's real releases list (GitHub already returns it newest-first) and
         * returns the first one that's actually installable: a well-formed vX.Y.Z.W tag
         * (skips legacy tags like "Release"/"26.2support" that predate that scheme),
         * accepted by the current pre-release policy, genuinely newer than the installed
         * version, and carrying a usable .apk or .zip asset. A release can be newer but
         * have nothing to install (v1.0.0.1 shipped with zero assets) - that's skipped in
         * favor of the next real candidate rather than failing the whole check. Returns
         * null once we reach a release that isn't newer (everything after it, being
         * older still, can't be either) - which also covers "already on the latest".
         */
        private fun pickLatestRelease(releases: JSONArray): LauncherVersion? {
            val currentVersionName = ZHTools.getVersionName()
            for (i in 0 until releases.length()) {
                val entry = releases.getJSONObject(i)
                if (entry.optBoolean("draft", false)) continue

                val versionName = entry.optString("tag_name", "").removePrefix("v").removePrefix("V")
                if (!DOTTED_VERSION_REGEX.matches(versionName)) continue

                val isPreRelease = entry.optBoolean("prerelease", false)
                val acceptedByPolicy = !isPreRelease || ZHTools.isPreRelease() || AllSettings.acceptPreReleaseUpdates.getValue()
                if (!acceptedByPolicy) continue

                if (compareDottedVersions(versionName, currentVersionName) <= 0) return null

                val asset = pickBestAsset(entry.optJSONArray("assets")) ?: continue

                val title = entry.optString("name", versionName)
                val body = entry.optString("body", "")
                val size = asset.optLong("size", 0L)

                return LauncherVersion(
                    0, // not used for comparisons anymore - versionName drives that (see compareDottedVersions above)
                    versionName,
                    LauncherVersion.WhatsNew(title, title, title), // GitHub releases aren't per-locale; same text in all three slots
                    LauncherVersion.WhatsNew(body, body, body),
                    entry.optString("published_at", ""),
                    LauncherVersion.FileSize(size, size, size, size, size), // one universal asset per release, not per-ABI splits
                    isPreRelease,
                    asset.optString("browser_download_url", ""),
                    asset.optString("name", "")
                )
            }
            return null
        }

        /**
         * Prefers a directly-installable .apk asset; falls back to a .zip, which is what
         * Endiq's newer releases (e.g. v1.0.0.3) actually ship instead of a raw apk -
         * UpdateLauncher unzips it to find the real .apk inside. If a release ever has
         * several matching assets (per-ABI splits), prefers whichever name contains the
         * device's own ABI string.
         */
        private fun pickBestAsset(assets: JSONArray?): JSONObject? {
            if (assets == null || assets.length() == 0) return null
            val assetList: List<JSONObject> = (0 until assets.length()).map { assets.getJSONObject(it) }
            val abi: String = getArchModel() ?: ""

            fun firstMatching(matchesExtension: (String) -> Boolean): JSONObject? {
                var abiMatch: JSONObject? = null
                var anyMatch: JSONObject? = null
                for (candidate in assetList) {
                    val name = candidate.optString("name", "")
                    if (!matchesExtension(name.lowercase())) continue
                    if (anyMatch == null) anyMatch = candidate
                    if (abi.isNotEmpty() && name.contains(abi, ignoreCase = true)) abiMatch = candidate
                }
                return abiMatch ?: anyMatch
            }

            return firstMatching { it.endsWith(".apk") } ?: firstMatching { it.endsWith(".zip") }
        }

        /**
         * Segment-by-segment comparison of dotted numeric version strings (e.g. "1.0.0.3"),
         * needed because a GitHub tag has no numeric versionCode the way a Play/manifest
         * release does. Missing trailing segments count as 0, so "1.1" == "1.1.0".
         */
        private fun compareDottedVersions(a: String, b: String): Int {
            val aParts = a.split(".")
            val bParts = b.split(".")
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val ai = aParts.getOrNull(i)?.toIntOrNull() ?: 0
                val bi = bParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (ai != bi) return ai - bi
            }
            return 0
        }

        @JvmStatic
        fun showFailToast(context: Context, resString: String) {
            runInUIThread {
                Toast.makeText(context, resString, Toast.LENGTH_SHORT).show()
            }
        }

        @JvmStatic
        fun getArchModel(arch: Int = Tools.DEVICE_ARCHITECTURE): String? {
            if (arch == Architecture.ARCH_ARM64) return "arm64-v8a"
            if (arch == Architecture.ARCH_ARM) return "armeabi-v7a"
            if (arch == Architecture.ARCH_X86_64) return "x86_64"
            if (arch == Architecture.ARCH_X86) return "x86"
            return null
        }

        @JvmStatic
        fun getFileSize(fileSize: FileSize): Long {
            val arch = Tools.DEVICE_ARCHITECTURE
            if (arch == Architecture.ARCH_ARM64) return fileSize.arm64
            if (arch == Architecture.ARCH_ARM) return fileSize.arm
            if (arch == Architecture.ARCH_X86_64) return fileSize.x86_64
            if (arch == Architecture.ARCH_X86) return fileSize.x86
            return fileSize.all
        }

        @JvmStatic
        fun installApk(context: Context, outputFile: File) {
            runInUIThread {
                TipDialog.Builder(context)
                    .setTitle(R.string.update)
                    .setMessage(StringUtils.insertNewline(context.getString(R.string.update_success), outputFile.absolutePath))
                    .setCenterMessage(false)
                    .setCancelable(false)
                    .setConfirmClickListener {
                        // Install.
                        val intent = Intent(Intent.ACTION_VIEW)
                        val apkUri = FileProvider.getUriForFile(context, context.packageName + ".provider", outputFile)
                        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(intent)
                    }.showDialog()
            }
        }
    }
}