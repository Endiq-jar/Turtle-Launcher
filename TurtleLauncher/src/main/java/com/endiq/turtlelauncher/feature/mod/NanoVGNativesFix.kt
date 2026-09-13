package com.endiq.turtlelauncher.feature.mod

import android.content.Context
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File

/**
 * TurtleLauncher: fixes a real, documented crash for Fabric/Quilt mods that use
 * `org.lwjgl.nanovg` for custom-rendered GUIs (roadmap item 22, "add liblwjgl_nanovg.so").
 *
 * This is NOT about this launcher's own bundled LWJGL natives - those already ship a correct
 * Bionic-built liblwjgl_nanovg.so for every ABI (see lwjgl3-natives-release.aar / jniLibs).
 * The actual gap is on the *mod* side: individual Fabric mods that depend on
 * `org.lwjgl:lwjgl-nanovg` typically only bundle desktop natives inside their own mod jar
 * (windows/linux/macos x86_64). LWJGL's native loader searches classpath resources in jar
 * order and can resolve to that mod's own bundled (glibc, x86_64) native instead of this
 * launcher's correct one - and even where it wouldn't, LWJGL's official Maven Central
 * `linux-arm64` natives are themselves built against glibc, not Android's Bionic libc, so they
 * fail to `dlopen` on-device either way:
 *
 *   java.lang.UnsatisfiedLinkError: Failed to locate library: liblwjgl_nanovg.so
 *     at org.lwjgl.nanovg.LibNanoVG.<clinit>
 *
 * The fix (same technique used by the small Fabric "library mod" this was sourced from -
 * see below) is to make sure a jar providing the correct Android Bionic build of
 * liblwjgl_nanovg.so is *also* on the game's classpath, so LWJGL's loader has a working
 * resource to find regardless of what any individual mod bundled. It's inert for any instance
 * that never touches org.lwjgl.nanovg - the natives just sit unused in the mods folder.
 *
 * Source: github.com/menearmenear/LWJGL-NanoVG-native-libraries (BSD-3-Clause), which builds
 * Android arm64/arm32 Bionic natives from MojoLauncher/unilwjgl3-builder plus windows-arm64
 * from Maven Central. Bundled here as a compat_mods asset rather than fetched at install time
 * since it's a tiny (~500KB), rarely-updated, correctness fix rather than user-facing content -
 * same reasoning as bundling the renderer .so files directly instead of downloading them.
 *
 * TurtleLauncher patch (see latestlog.txt bug report): upstream's fabric.mod.json shipped with
 * `"minecraft": "~26.1.2"` in its depends block, which is wrong for a plain native-library
 * shim - it has no actual Minecraft-version dependency, it just provides .so files for LWJGL's
 * classpath native loader. That stray constraint made Fabric hard-fail resolution on every
 * instance except 26.1.2, e.g. `HARD_DEP_INCOMPATIBLE_PRESELECTED lwjgl-nanovg-natives 1.0.2
 * {depends minecraft @ [~26.1.2]}` on 1.21.11. The bundled jar here has that one line removed
 * from its fabric.mod.json (repackaged, natives/icon/everything else untouched) - if this asset
 * is ever re-synced from upstream, re-apply that edit or the crash comes back.
 */
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
