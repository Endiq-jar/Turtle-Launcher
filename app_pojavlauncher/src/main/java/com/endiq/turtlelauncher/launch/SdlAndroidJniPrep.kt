package com.endiq.turtlelauncher.launch

import android.app.Activity
import android.util.Log
import com.endiq.turtlelauncher.utils.path.PathManager
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SdlAndroidJniPrep {
    private const val TAG = "SdlAndroidJniPrep"

    /** How long to wait for the UI thread to build the SDLSurface (see createSurfaceOnUiThread). */
    private const val SURFACE_WAIT_MS = 3000L

    @JvmStatic
    @Volatile
    var isActive: Boolean = false
        private set

    @JvmStatic
    fun ensureSingleSdl3Source(versionName: String?) {
        // ── Layer 1b: purge shadowing SDL3 copies from the natives cache dir ──
        if (versionName != null) {
            val nativesDir = File(PathManager.DIR_CACHE, "natives/$versionName")
            val shadows = runCatching {
                nativesDir.listFiles { f -> f.isFile && f.name.startsWith("libSDL3") && f.name.contains(".so") }
                    ?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
            for (shadow in shadows) {
                val deleted = runCatching { shadow.delete() }.getOrDefault(false)
                // "TurtleSDL3" is the tag MinecraftGLSurface/SDLActivity already use for this
                // integration's diagnostics - keep every SDL3 triage line greppable together.
                Log.w(TAG, "TurtleSDL3: removed foreign ${shadow.name} (${shadow.length()} bytes, " +
                    "deleted=$deleted) from natives cache - it would shadow the launcher's own " +
                    "libSDL3.so on java.library.path and load uninitialized (see SdlAndroidJniPrep doc)")
            }
        }

        // ── Layer 1c: state what the game SHOULD load, into the surviving log ──
        val apkSdl = File(PathManager.DIR_NATIVE_LIB, "libSDL3.so")
        if (!apkSdl.isFile) {
            Log.e(TAG, "TurtleSDL3: no libSDL3.so in ${PathManager.DIR_NATIVE_LIB}?! " +
                "SDL versions cannot work without it - broken/split APK install?")
            return
        }
        val isAndroidBuild = runCatching {
            apkSdl.inputStream().use { input ->
                // Read in chunks; the marker is short so a sliding join is enough.
                var tail = ByteArray(0)
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    val window = tail + chunk.copyOf(n)
                    if (String(window, Charsets.US_ASCII).contains("SDL_GetAndroidJNIEnv")) return@use true
                    // Keep only the tail that could still complete a marker match.
                    tail = window.copyOfRange(maxOf(0, window.size - 64), window.size)
                }
                false
            }
        }.getOrDefault(false)
        Log.i(TAG, "TurtleSDL3: pinned SDL3 for this launch = ${apkSdl.absolutePath} " +
            "(${apkSdl.length()} bytes, Android build: $isAndroidBuild); " +
            "-Dorg.lwjgl.sdl.libname is set to this same file by LaunchArgs")
    }

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
            // 26.3+ features will be degraded, but the game can still start. NOTE: this is the
            // ART side only. If this fails while the game still launches, LWJGL's own dlopen of
            // SDL3 will get an instance whose mJavaVM/mActivityClass never got set, and SDL's
            // Android backend will crash inside Android_JNI_InitTouch (see class doc) - that is
            // exactly what CrashAnalyzer rule 22 decodes for the user afterwards.
            Log.e(TAG, "SDL prepare failed - the game-side SDL3 instance will be uninitialized, " +
                "expect the sdl3_android_init_sigsegv crash if the game reaches SDL_Init", e)
        }
    }

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
