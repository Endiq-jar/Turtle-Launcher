package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import android.os.Process
import androidx.tracing.Trace
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TurtleLauncher v10: while the user is sitting on the main menu (version list),
 * this warms the on-disk/Glide caches for every version's icon file and touches
 * each version's mods folder listing, so switching to a version or opening its
 * mod list doesn't pay a cold-cache cost the first time. Deliberately cheap and
 * best-effort — any failure for one version is skipped, never surfaced to the UI.
 *
 * Runs its worker threads at Process.THREAD_PRIORITY_BACKGROUND: this work is pure
 * "nice to have if the CPU is free" - on a low-end device where the UI thread and any
 * running renderer/game threads are already fighting for cycles, this keeps prefetching
 * from ever being the reason a tap feels delayed.
 */
object AssetPrefetcher {
    private const val TAG = "AssetPrefetcher"

    private val threadNumber = AtomicInteger(0)
    private val backgroundThreadFactory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "AssetPrefetch-${threadNumber.incrementAndGet()}")
    }
    private val executor = Executors.newFixedThreadPool(2, backgroundThreadFactory)
    private val alreadyRunning = AtomicBoolean(false)

    @JvmStatic
    fun prefetch(context: Context) {
        if (!AllSettings.backgroundAssetPrefetch.getValue()) return
        // Background Services (item 20) - "pause indexing": prefetch() only ever gets
        // triggered from LauncherActivity.onResume(), which by definition can't fire while
        // a game session has that Activity stopped behind Minecraft - so this genuinely
        // stops a *new* version-wide directory-listing pass from ever starting mid-session.
        // Doesn't touch an already-in-flight pass (this executor's threads already run at
        // THREAD_PRIORITY_BACKGROUND, and TaskExecutors' own "never cancel in-flight work"
        // rule applies here too), just prevents redundant restarts from stacking on top.
        if (com.endiq.turtlelauncher.task.TaskExecutors.isGameSessionActive) return
        if (!alreadyRunning.compareAndSet(false, true)) return

        executor.execute {
            Trace.beginSection("AssetPrefetcher.prefetch")
            try {
                val versions = VersionsManager.getVersions()
                versions.forEach { version ->
                    runCatching {
                        // Touch the icon file so it's already in the page cache by the time
                        // an ImageView/Glide requests it (decoding still happens on demand,
                        // but the slow part — cold disk I/O — is already paid for).
                        val iconFile = VersionsManager.getVersionIconFile(version)
                        if (iconFile.exists()) iconFile.inputStream().use { it.read(ByteArray(1)) }

                        // Warm the mods/resourcepacks/shaderpacks directory listings
                        // (readdir) ahead of time - resourcepacks/shaderpacks added
                        // alongside the new Performance Heatmap feature (HeatmapAnalyzer),
                        // which reads these same listings the moment the Files tab opens.
                        val gameDir = version.getGameDir()
                        listOf("mods", "resourcepacks", "shaderpacks").forEach { dirName ->
                            val dir = java.io.File(gameDir, dirName)
                            if (dir.isDirectory) dir.listFiles()
                        }
                    }.onFailure { e ->
                        Logging.i(TAG, "Prefetch skipped for ${version.getVersionName()}: ${e.message}")
                    }
                }
            } finally {
                Trace.endSection()
                alreadyRunning.set(false)
            }
        }
    }
}
