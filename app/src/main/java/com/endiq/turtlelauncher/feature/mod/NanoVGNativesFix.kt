package com.endiq.turtlelauncher.feature.mod

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Installs the original Turtle NanoVG compatibility mod for Fabric/Quilt instances.
 *
 * The bundled jar supplies the native-classpath bridge expected by the LWJGL NanoVG
 * implementation. Installation is local, idempotent, and deliberately best-effort so a
 * missing/corrupt compatibility asset can never prevent a vanilla or Forge launch.
 */
object NanoVGNativesFix {
    private const val TAG = "NanoVGNativesFix"
    private const val ASSET_PATH = "compat_mods/lwjgl-nanovg-natives-1.0.2.jar"
    private const val TARGET_FILENAME = "turtle-lwjgl-nanovg-natives-fix.jar"

    fun ensureInstalled(
        context: Context,
        loaderType: String?,
        mainClass: String?,
        modsDir: File,
    ) {
        val loader = loaderType.orEmpty().lowercase()
        val entryPoint = mainClass.orEmpty().lowercase()
        val fabricLike = loader == "fabric" || loader == "quilt" ||
            entryPoint.contains("fabric") || entryPoint.contains("quilt") ||
            entryPoint.contains("knot")
        if (!fabricLike) return

        val target = File(modsDir, TARGET_FILENAME)
        if (target.isFile && target.length() > 0L) return

        runCatching {
            modsDir.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Installed NanoVG compatibility mod in ${modsDir.absolutePath}")
        }.onFailure { error ->
            target.delete()
            Log.w(TAG, "NanoVG compatibility mod could not be installed", error)
        }
    }
}
