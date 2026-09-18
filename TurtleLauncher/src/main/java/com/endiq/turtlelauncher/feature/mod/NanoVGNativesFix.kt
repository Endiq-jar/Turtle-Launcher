package com.endiq.turtlelauncher.feature.mod

import android.content.Context
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File

object NanoVGNativesFix {
    private const val TAG = "NanoVGNativesFix"
    private const val ASSET_PATH = "compat_mods/lwjgl-nanovg-natives-1.0.2.jar"
    private const val TARGET_FILENAME = "turtle-lwjgl-nanovg-natives-fix.jar"

    /**
     * Copies the bundled nanovg-natives compatibility jar into [modsDir] if this instance's
     * loader can use it and it isn't already there. Safe to call unconditionally on every
     * launch - idempotent, local-only (no network), and a no-op for Forge/NeoForge/vanilla
     * instances where it wouldn't do anything useful anyway (this exists specifically for
     * Fabric's/Quilt's classpath model).
     */
    @JvmStatic
    fun ensureInstalled(context: Context, loader: ModLoader?, modsDir: File) {
        if (loader != ModLoader.FABRIC && loader != ModLoader.QUILT) return

        val target = File(modsDir, TARGET_FILENAME)
        if (target.exists() && target.length() > 0) return

        try {
            modsDir.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Logging.i(TAG, "Installed nanovg natives compatibility fix into ${modsDir.path}")
        } catch (t: Throwable) {
            // Never let a compatibility-fix copy failure block an actual game launch.
            Logging.e(TAG, "Failed to install nanovg natives compatibility fix", t)
            target.delete()
        }
    }
}
