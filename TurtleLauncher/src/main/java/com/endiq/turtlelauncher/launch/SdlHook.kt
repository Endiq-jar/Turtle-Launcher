package com.endiq.turtlelauncher.launch

import android.util.Log
import androidx.annotation.Keep

/**
 * Loads libsdlhook.so (see TurtleLauncher/src/main/jni/sdlhook/) and installs
 * its SDL hooks. Must run before the game first touches SDL — and before
 * System.loadLibrary("SDL3") — so SdlAndroidJniPrep.setup() calls [install]
 * as its first step on SDL launches.
 */
@Keep
object SdlHook {
    private const val TAG = "SdlHook"

    @JvmStatic
    @Volatile
    var installed: Boolean = false
        private set

    /**
     * Installs the SDL hooks. Idempotent; returns true when the hooks are
     * active afterwards.
     */
    @JvmStatic
    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        try {
            System.loadLibrary("sdlhook")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Could not load libsdlhook.so - SDL launches will crash at the Mojang logo", e)
            return false
        }
        val status = try {
            nativeInit()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libsdlhook.so has no nativeInit - stale prebuilt?", e)
            return false
        }
        if (status != 0) {
            Log.e(TAG, "libsdlhook nativeInit failed with status $status (is libbytehook.so present?)")
            return false
        }
        installed = true
        Log.i(TAG, "SDL hooks installed")
        return true
    }

    @JvmStatic
    private external fun nativeInit(): Int
}
