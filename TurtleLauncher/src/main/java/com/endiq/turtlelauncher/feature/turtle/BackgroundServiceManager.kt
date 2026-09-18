package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import com.bumptech.glide.Glide
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings

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
    }
}
