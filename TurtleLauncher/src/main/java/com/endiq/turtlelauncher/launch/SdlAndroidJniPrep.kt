package com.endiq.turtlelauncher.launch

import android.app.Activity
import android.util.Log
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SDL initialization when it needs to be done from JNI.
 *
 * WHY THIS EXISTS: starting with Minecraft 26.3 (SDL 3), SDL creates its EGL window surface
 * from the game thread on its own, without an Android Surface being passed in first. SDL
 * therefore needs the host Activity, the SDLSurface instance and the native surface handle to
 * already be set up on the ART side when the JNI calls start. Amethyst solves this with a native
 * `sdl_hook.c` that patches `SDL_InitSubSystem()` and calls `CallbackBridge.notifyLauncher()`,
 * which then runs SDLActivity's setup on demand.
 *
 * WHAT IS AND ISN'T PORTED: TurtleLauncher ships `libpojavexec.so` prebuilt with no native
 * sources and no NDK toolchain here, so the `sdl_hook.c` half cannot be ported. What this class
 * does instead is mirror Amethyst's ART-side setup sequence, executed ahead of time on the host
 * side just before the JVM starts (from JREUtils.launchJavaVM). `setupJNI()`'s `()I` signature
 * is kept - Amethyst's `nativeSetupJNI()` returns void, but this build's libSDL3.so registers
 * the `()I` variant and a mismatched signature aborts on the first call. Amethyst also calls
 * `SDLActivity.getSDLSurface().nativeResize(...)` on size changes; this launcher's libSDL3.so
 * exports no `nativeResize` (checked in the binary), so resizes go through
 * SDLSurface.surfaceChanged() instead - see MinecraftGLSurface.notifySdlOfSurfaceSize().
 *
 * STATUS: the previously *confirmed* abort (jar/.so `nativeSetupJNI` signature mismatch) stays
 * fixed. The original SIGSEGV this class exists to prevent is NOT confirmed fixed - this
 * environment has no device or emulator to test on, and the native half of Amethyst's fix is
 * the part that could not be ported. Treat "26.3 launches" as unverified until it runs on a
 * real device; CrashAnalyzer's rule 22 says the same thing to the user when this path crashes.
 */
object SdlAndroidJniPrep {
    private const val TAG = "SdlAndroidJniPrep"

    /** How long to wait for the UI thread to build the SDLSurface (see createSurfaceOnUiThread). */
    private const val SURFACE_WAIT_MS = 3000L

    /**
     * True once [setup] has completed, i.e. SDL's Java glue is registered for this process.
     *
     * MinecraftGLSurface checks this from Java (`SdlAndroidJniPrep.isActive()`) before touching
     * anything SDL-related, so a plain GLFW launch never enters the SDL surface path. Deliberately
     * never reset: setup() runs once per launch, and SDL's static state persists for the process.
     */
    @JvmStatic
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * @param activity the Activity used as the SDL host. Must not be null.
     */
    @JvmStatic
    fun setup(activity: Activity?) {
        if (activity == null) {
            Log.e(TAG, "Cannot prepare SDL host state without an Activity")
            return
        }
        try {
            // Must be loaded before the SDL Java methods below are called - SDL3 isn't in the
            // default library set (libpojavexec's dependency on it isn't always resolved by the
            // time this runs).
            System.loadLibrary("SDL3")
            // Must run first: SDL.initialize() nulls SDLActivity's static state (context,
            // clipboard handler, input managers, surface), so anything assigned before it would
            // be wiped out.
            SDL.initialize()
            SDL.setContext(activity)

            // SDLSurface is a View; build it on the UI thread rather than on whatever thread the
            // launch pipeline happens to be running on.
            val sdlSurface = createSurfaceOnUiThread(activity)

            // externalInitialize assigns the Activity, the SDLSurface and the layout, installs
            // the clipboard handler and cursor list, and registers the (still null) native
            // surface so SDL can find everything when it starts.
            SDLActivity.externalInitialize(sdlSurface, null, null)
            SDL.setupJNI()

            // From here on MinecraftGLSurface forwards its own Surface callbacks to SDL's
            // SDLSurface, which is the only way SDL learns the real window size (see
            // notifySdlOfSurfaceSize()). isActive is the flag that switches that path on -
            // MinecraftGLSurface reads it from Java as SdlAndroidJniPrep.isActive().
            isActive = true
            Log.i(TAG, "SDL host state prepared")
        } catch (e: Throwable) {
            // Best effort. Failing here must not take down the launch - without SDL set up some
            // 26.3+ features will be degraded, but the game can still start.
            Log.e(TAG, "SDL prepare failed", e)
        }
    }

    /**
     * Creates the [SDLSurface] on the UI thread and waits for it.
     *
     * Returns null if the UI thread does not respond in time (a wedged main thread would
     * otherwise hang the launch pipeline here forever).
     */
    private fun createSurfaceOnUiThread(activity: Activity): SDLSurface? {
        if (activity.isFinishing || activity.isDestroyed) return null
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return SDLSurface(activity)
        }
        var result: SDLSurface? = null
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                result = SDLSurface(activity)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not create SDLSurface", e)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(SURFACE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "UI thread did not build the SDLSurface in ${SURFACE_WAIT_MS}ms")
            return null
        }
        return result
    }
}
