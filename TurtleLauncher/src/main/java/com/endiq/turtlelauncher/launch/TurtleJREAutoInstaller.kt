package com.endiq.turtlelauncher.launch

import android.content.Context
import com.kdt.mcgui.ProgressLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.utils.DownloadUtils
import java.io.File
import java.io.FileInputStream

/**
 * TurtleJREAutoInstaller
 *
 * Mirrors Mojo Launcher's NewJREUtil logic:
 * automatically downloads and installs the required Java runtime before launch.
 *
 * ── Java version requirement mapping ──────────────────────────────────────
 * Strict 4-tier mapping (exact match, no "round up to nearest" fallback):
 *
 *   Java    MC versions
 *   ────    ──────────────────────────
 *   JRE 8   MC 1.16.5 and below
 *   JRE 17  MC 1.18 – 1.20.4
 *   JRE 21  MC 1.20.5 – 1.21.11
 *   JRE 25  MC 26.1 and later
 *
 * The actual MC-version → Java-major resolution happens in
 * LaunchArgs.resolveRequiredJava(); this class only handles downloading
 * and installing whichever exact Java major version it's asked for.
 *
 * ── Internal runtime names ────────────────────────────────────────────────
 *   Internal-8   → Java 8
 *   Internal-17  → Java 17
 *   Internal-21  → Java 21
 *   Internal-25  → Java 25   ← new for MC 26.x
 *
 * The CDN URL pattern (Mojo CDN):
 *   https://mojolauncher.github.io/jre-download/<cdnPath>/universal.tar.xz
 *   https://mojolauncher.github.io/jre-download/<cdnPath>/bin-<arch>.tar.xz
 */
object TurtleJREAutoInstaller {

    private const val DOWNLOAD_BASE = "https://mojolauncher.github.io/jre-download/"

    /** Re-check interval: 3 days in seconds */
    private const val UPDATE_INTERVAL_SECONDS = 259200L

    private data class InternalJre(
        val major: Int,
        val name: String,
        val cdnPath: String,
        val description: String
    )

    /**
     * Known runtimes, one per tier of the 4-tier table above.
     * cdnPath must match the Mojo CDN directory structure.
     */
    private val INTERNAL_JRES = listOf(
        InternalJre(8,  "Internal-8",  "components/jre-legacy", "Java 8  (MC 1.16.5 and below)"),
        InternalJre(17, "Internal-17", "components/jre-new",    "Java 17 (MC 1.18 – 1.20.4)"),
        InternalJre(21, "Internal-21", "components/jre-21",     "Java 21 (MC 1.20.5 – 1.21.11)"),
        InternalJre(25, "Internal-25", "components/jre-25",     "Java 25 (MC 26.1 and later)")
    )

    /**
     * Main entry point. Called from [LaunchGame.getRuntime] before game launch.
     *
     * Downloads and installs the correct Java version if:
     *  - It is not yet installed, OR
     *  - The last update check is older than [UPDATE_INTERVAL_SECONDS].
     *
     * @param context      Activity context (for UI-thread callbacks if needed)
     * @param requiredJava Java major version required by this Minecraft version
     * @return             Installed runtime name (e.g. "Internal-25"), or null on total failure
     */
    fun ensureJavaInstalled(context: Context, requiredJava: Int): String? {
        val target = pickBestJre(requiredJava)
        if (target == null) {
            Logging.e("TurtleJREAutoInstaller", "No JRE mapping for Java $requiredJava")
            return null
        }

        Logging.i("TurtleJREAutoInstaller",
            "MC requires Java $requiredJava → ${target.description} (${target.name})")

        // Fast path: already installed and update interval not exceeded
        val alreadyInstalled = MultiRTUtils.readInternalRuntimeVersion(target.name) != null
        if (alreadyInstalled && !isUpdateDue(target.name)) {
            Logging.i("TurtleJREAutoInstaller", "${target.name} up-to-date, skipping download")
            return target.name
        }

        return try {
            downloadAndInstall(target)
            MultiRTUtils.writeLastUpdateTime(target.name, System.currentTimeMillis() / 1000L)
            Logging.i("TurtleJREAutoInstaller", "${target.name} installed OK")
            target.name
        } catch (e: Exception) {
            Logging.e("TurtleJREAutoInstaller", "Failed to install ${target.name}: ${e.message}", e)
            // Fall back to the nearest already-installed runtime
            val fallback = MultiRTUtils.getNearestJreName(requiredJava)
            Logging.i("TurtleJREAutoInstaller", "Falling back to: $fallback")
            fallback
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns the exact JRE entry for [required] (an exact Java major version,
     * as resolved by LaunchArgs.resolveRequiredJava — always one of 8/17/21/25).
     * Falls back to the closest available entry only if [required] doesn't
     * match any known tier (shouldn't normally happen).
     */
    private fun pickBestJre(required: Int): InternalJre? {
        return INTERNAL_JRES.firstOrNull { it.major == required }
            ?: INTERNAL_JRES
                .filter { it.major >= required }
                .minByOrNull { it.major }
            ?: INTERNAL_JRES.maxByOrNull { it.major }
    }

    /**
     * True if [UPDATE_INTERVAL_SECONDS] have elapsed since the last recorded update.
     * Missing timestamp is treated as "never updated".
     */
    private fun isUpdateDue(name: String): Boolean {
        val lastCheck = MultiRTUtils.readLastUpdateTime(name)
        if (lastCheck == -1L) return true
        val elapsed = System.currentTimeMillis() / 1000L - lastCheck
        return elapsed > UPDATE_INTERVAL_SECONDS
    }

    /**
     * Downloads the universal + platform-specific tar.xz packages from Mojo CDN,
     * then unpacks them with [MultiRTUtils.installRuntimeNamedBinpack].
     */
    private fun downloadAndInstall(jre: InternalJre) {
        val arch      = Architecture.archAsString(Tools.DEVICE_ARCHITECTURE)
        val baseUrl   = DOWNLOAD_BASE + jre.cdnPath + "/"
        val cacheDir  = PathManager.DIR_CACHE
        val univFile  = File(cacheDir, "jre-${jre.name}-universal.tar.xz")
        val platFile  = File(cacheDir, "jre-${jre.name}-bin-$arch.tar.xz")

        Logging.i("TurtleJREAutoInstaller", "Downloading ${jre.name} from $baseUrl")

        ProgressKeeper.submitProgress(
            ProgressLayout.UNPACK_RUNTIME, 0,
            R.string.downloading_java_runtime_uni, 0, 0, 0)

        try {
            // 1) Universal (class library) package
            Logging.i("TurtleJREAutoInstaller", "↓ universal.tar.xz")
            DownloadUtils.downloadFile(baseUrl + "universal.tar.xz", univFile)

            ProgressKeeper.submitProgress(
                ProgressLayout.UNPACK_RUNTIME, 40,
                R.string.downloading_java_runtime_platform)

            // 2) Platform-specific (JVM binaries) package
            Logging.i("TurtleJREAutoInstaller", "↓ bin-$arch.tar.xz")
            DownloadUtils.downloadFile(baseUrl + "bin-$arch.tar.xz", platFile)

            ProgressKeeper.submitProgress(
                ProgressLayout.UNPACK_RUNTIME, 80,
                R.string.turtle_java_autoinstall_start, jre.major)

            // 3) Unpack + register
            Logging.i("TurtleJREAutoInstaller", "Installing ${jre.name}…")
            FileInputStream(univFile).use { uni ->
                FileInputStream(platFile).use { plat ->
                    MultiRTUtils.installRuntimeNamedBinpack(
                        uni, plat, jre.name,
                        "turtle-auto-${jre.major}-${System.currentTimeMillis()}"
                    )
                }
            }

            MultiRTUtils.postPrepare(jre.name)
            MultiRTUtils.forceReread(jre.name)

        } finally {
            ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME)
            univFile.takeIf { it.exists() }?.delete()
            platFile.takeIf { it.exists() }?.delete()
        }
    }
}
