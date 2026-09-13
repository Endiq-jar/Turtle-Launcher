package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import com.bumptech.glide.Glide
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * TurtleLauncher: "Background Services" (spec item 20) - while Minecraft is running,
 * TaskExecutors.isGameSessionActive already deprioritizes the shared task-pool's OS
 * thread priority (Section 3, CPU Optimizations). This adds the remaining concrete
 * pieces the spec asks for on top of that same flag, all traced to real call sites
 * rather than invented ones:
 *
 *  - Pause unnecessary animations: FragmentWithAnim.playAnimation() now short-circuits
 *    on isGameSessionActive, same as the user's own "disable animations" toggle.
 *  - Pause update checker: PluginLoader's passive plugin-update network check (which
 *    genuinely re-fires every session, since MainActivity's :game process re-runs
 *    BaseActivity.onCreate()) now skips while a session is active.
 *  - Pause indexing: AssetPrefetcher's version-wide directory-listing pass now refuses
 *    to start a new run while a session is active.
 *  - Reduce launcher RAM usage: this file's onGameSessionStart() evicts Glide's
 *    in-memory bitmap cache (version icons, backgrounds, mod-list thumbnails) right
 *    before Minecraft starts, freeing that RAM for the game. Self-restoring by
 *    construction - Glide simply repopulates the cache lazily the next time an
 *    ImageView asks for something, same as the existing onLowMemory() handling in
 *    TurtleApplication already relies on.
 *  - Lower launcher CPU priority: already covered by TaskExecutors' existing
 *    beforeExecute() override; nothing further to add without touching the main
 *    thread's own priority, which isn't safe to lower out from under a launcher the
 *    user might switch back to at any moment.
 *  - Restore automatically after exit: nothing here needs an explicit restore step -
 *    every gate above simply stops applying the instant isGameSessionActive flips back
 *    to false in LauncherActivity.onResume(), and the Glide cache heals itself on next
 *    use. onGameSessionEnd() exists as the symmetric call site for any future addition
 *    that *does* need one.
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
            Glide.get(context).clearMemory()
        }.onFailure { t ->
            Logging.e(TAG, "Failed to trim Glide memory before game session", t)
        }
    }

    @JvmStatic
    fun onGameSessionEnd() {
        // Intentionally empty - see class doc. Kept as the symmetric restore-point call
        // site (mirrors TaskExecutors.setGameSessionActive(false) in the same onResume())
        // so a future addition that needs an explicit "undo" has somewhere real to go
        // instead of bolting onto onResume() directly.
    }
}
