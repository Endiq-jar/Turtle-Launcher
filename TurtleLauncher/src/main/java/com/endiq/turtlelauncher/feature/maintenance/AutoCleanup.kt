package com.endiq.turtlelauncher.feature.maintenance

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.utils.path.PathManager
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TurtleLauncher: unattended, age-based deletion of old Minecraft crash reports (per installed
 * version), old launcher logs, and stale/orphaned native-library extraction temp dirs.
 *
 * Distinct from CleanUpCache.kt, which is a manual full-wipe the user triggers from Settings -
 * this runs on its own, only touches files past the retention window, and rate-limits itself to
 * once a day so it doesn't add a filesystem scan to every single launch.
 */
object AutoCleanup {
    private const val RETENTION_DAYS = 14L
    private const val MIN_RUN_INTERVAL_MS = 24L * 60 * 60 * 1000

    @JvmStatic
    fun runIfDue() {
        if (!AllSettings.autoCleanupEnabled.getValue()) return
        val now = System.currentTimeMillis()
        val last = AllSettings.lastAutoCleanupTime.getValue()
        if (now - last < MIN_RUN_INTERVAL_MS) return
        AllSettings.lastAutoCleanupTime.put(now).save()

        Task.runTask {
            runCatching { performCleanup() }
                .onFailure { e -> Logging.e("AutoCleanup", "Automatic cleanup failed", e) }
        }.execute()
    }

    private fun performCleanup() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        var deletedCount = 0
        var freedBytes = 0L

        // Old Minecraft crash reports. Several installed versions can share the same game dir
        // when version isolation is off, so dedupe the resolved directories first rather than
        // rescanning the same folder once per version pointing at it.
        val crashDirs = VersionsManager.getVersions()
            .map { File(it.getGameDir(), "crash-reports") }
            .distinct()
        for (dir in crashDirs) {
            dir.listFiles { f -> f.isFile }?.forEach { f ->
                if (f.lastModified() < cutoff) {
                    freedBytes += FileUtils.sizeOf(f)
                    if (FileUtils.deleteQuietly(f)) deletedCount++
                }
            }
        }

        // Old launcher logs. Logging.kt already ring-buffers these to at most 10 files x 15MB,
        // this is just a proactive reclaim for ones that have been sitting past the retention
        // window (e.g. the launcher went unused for a while).
        File(PathManager.DIR_LAUNCHER_LOG).listFiles { f -> f.isFile }?.forEach { f ->
            if (f.lastModified() < cutoff) {
                freedBytes += FileUtils.sizeOf(f)
                if (FileUtils.deleteQuietly(f)) deletedCount++
            }
        }

        // Stale/orphaned per-version native-library extraction caches. Safe to remove either
        // way - re-extracted from the APK's own native libs automatically on next launch.
        val knownVersionNames = VersionsManager.getVersions().map { it.getVersionName() }.toSet()
        File(PathManager.DIR_CACHE, "natives").listFiles { f -> f.isDirectory }?.forEach { dir ->
            val orphaned = dir.name !in knownVersionNames
            val stale = dir.lastModified() < cutoff
            if (orphaned || stale) {
                freedBytes += FileUtils.sizeOfDirectory(dir)
                if (FileUtils.deleteQuietly(dir)) deletedCount++
            }
        }

        if (deletedCount > 0) {
            Logging.i("AutoCleanup", "Removed $deletedCount old file(s)/dir(s), freed ~${freedBytes / 1024}KB")
        }
    }
}
