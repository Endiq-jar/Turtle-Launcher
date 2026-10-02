package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import com.bumptech.glide.Glide
import com.endiq.turtlelauncher.feature.download.InfoCache
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Launcher-side memory management for a game session.
 *
 * The promise shown to the user (Settings -> Experimental -> Background Service Optimization)
 * is that while Minecraft runs, the launcher stops competing with it for CPU and RAM. Most of
 * that is enforced elsewhere by [com.endiq.turtlelauncher.task.TaskExecutors.isGameSessionActive]:
 * launcher animations, the plugin-update check and asset prefetching all read that flag and
 * stand down. This object is the other half - actually releasing the memory the launcher is
 * holding, instead of just promising not to allocate more.
 *
 * What this deliberately does NOT do:
 *  - No world, save, mod, log or shader-cache deletion. Disk caches are storage, not RAM, and
 *    clearing them is exactly what makes the next launch slow.
 *  - No Activity teardown. The launcher stays resident so coming back from the game is
 *    instant; it just lowers its own thread priority and stops starting background work.
 *
 * The full policy (and the wording the assistant uses when explaining it to a player) lives in
 * com.endiq.turtlelauncher.feature.ai.TurtleAiPrompt.LAUNCHER_SIDE_MEMORY_MANAGEMENT.
 */
object BackgroundServiceManager {
    private const val TAG = "BackgroundServiceManager"

    @JvmStatic
    fun onGameSessionStart(context: Context) {
        if (!AllSettings.backgroundServiceOptimization.getValue()) return
        // Must run on the main thread - Glide's memory cache/bitmap pool isn't
        // thread-safe for this call, and both real call sites (ContextAwareDoneListener's
        // executeWithActivity(), just like TaskExecutors.setGameSessionActive(true)
        // right above it) are already on the main/activity thread.
        runCatching {
            // 1. Decoded images are the largest thing the launcher holds: version icons,
            //    screenshots and the launcher background, plus Glide's bitmap pool. This is
            //    also the "don't keep large images/textures in memory while the game runs"
            //    half of the policy - Glide re-decodes from disk on the next request, so the
            //    launcher UI still looks right when the player comes back.
            Glide.get(context).clearMemory()
            // 2. Search results the Download screen is holding (mod, dependency, version and
            //    modpack-version lookups). Pure in-memory cache: the next visit refetches only
            //    what it needs, and nothing was written to disk in the first place.
            InfoCache.clearAll()
        }.onFailure { t ->
            Logging.e(TAG, "Failed to release launcher caches before game session", t)
        }
    }

    /**
     * Counterpart hook for [onGameSessionStart].
     *
     * Nothing needs undoing explicitly: the caches released above refill on demand, and the
     * work that stood down did so by reading
     * [com.endiq.turtlelauncher.task.TaskExecutors.isGameSessionActive], which the caller has
     * already flipped back to false before calling this (LauncherActivity's resume path). Kept
     * as a real method so a future pre-session change has an obvious place to undo itself.
     */
    @JvmStatic
    fun onGameSessionEnd() {
    }
}
