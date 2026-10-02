package com.endiq.turtlelauncher.launch

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import com.endiq.turtlelauncher.game.sdl.SdlBridge
import com.endiq.turtlelauncher.utils.path.PathManager
import org.libsdl.app.SDLActivity
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SdlAndroidJniPrep {
    private const val TAG = "SdlAndroidJniPrep"

    /** How long to wait for the UI thread to run the SDL enable step (see setup). */
    private const val SETUP_WAIT_MS = 3000L

    @JvmStatic
    fun ensureMobileGluesShaderErrorIgnore() {
        runCatching {
            val dir = File(PathManager.DIR_FILE, "mobileglues").apply { mkdirs() }
            val config = File(dir, "config.json")
            if (!config.exists()) {
                config.writeText("{\"enableNoError\":2}\n")
                Log.i(TAG, "TurtleSDL3: wrote ${config.absolutePath} (enableNoError=2 = ignore shader/program " +
                    "errors, which MobileGlues' release notes require for MC 26.3-snapshot-3+)")
            }
        }.onFailure { Log.w(TAG, "TurtleSDL3: could not write MobileGlues config.json", it) }
    }

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
     * Eagerly prepares the ART-side SDL host state, before the JVM starts.
     *
     * This is Turtle's equivalent of ZalithLauncher2's lazy SDL bring-up
     * (sdl_hook's SDL_Init hook -> CallbackBridge.notifyLauncher): install the
     * native hooks, load SDL3+SDL2, set up JNI, register the content layout,
     * and flip SdlBridge.sdlEnabled on. The game Surface itself usually already
     * exists by now (registered via SdlBridge.prepareSurface from
     * MinecraftGLSurface's callbacks) or arrives later; both orders work.
     *
     * @param activity the Activity used as the SDL host. Must not be null.
     */
    @JvmStatic
    fun setup(activity: Activity?) {
        if (activity == null) {
            Log.e(TAG, "Cannot prepare SDL host state without an Activity")
            return
        }
        if (!SdlBridge.markSdlInitialized()) {
            Log.i(TAG, "SDL host state already prepared")
            return
        }
        try {
            // First: the native hooks must be in place before anything resolves
            // SDL symbols (including this thread's System.loadLibrary below and
            // the game's later dlopen). Without them MC 26.3+ dies at the
            // Mojang logo (second SDL_CreateWindow refused, desktop GL profile
            // rejected); see TurtleLauncher/src/main/jni/sdlhook/README.md.
            if (!SdlHook.install()) {
                Log.e(TAG, "TurtleSDL3: SDL hooks did not install - continuing anyway, " +
                    "but the game will likely crash during SDL init")
            }
            System.loadLibrary("SDL3")
            System.loadLibrary("SDL2")
            SdlBridge.setupJNI()

            // SDLSurface/externalInitialize touch Views: run on the UI thread,
            // and wait for it so sdlEnabled is only set once SDL can actually
            // find everything (mirrors Zalith's notifyLauncher tail).
            val latch = CountDownLatch(1)
            activity.runOnUiThread {
                try {
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    SdlBridge.prepareSurface(activity, null, content, null)
                    SdlBridge.sdlEnabled = true
                    SDLActivity.getSDLSurface()?.let { surface ->
                        surface.surfaceChanged()
                        val w = CallbackBridge.windowWidth
                        val h = CallbackBridge.windowHeight
                        if (w > 0 && h > 0) surface.nativeResize(w, h)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "SDL enable step failed", e)
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(SETUP_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "UI thread did not run the SDL enable step in ${SETUP_WAIT_MS}ms")
            }

            // From here on MinecraftGLSurface forwards its own Surface callbacks
            // to SDL (see publishSurfaceToSdl/notifySdlOfSurfaceSize), which is
            // how SDL learns the real window size. SdlBridge.sdlEnabled is the
            // flag that switches that path on.
            Log.i(TAG, "TurtleSDL3: SDL host state prepared (sdlEnabled=${SdlBridge.sdlEnabled})")
        } catch (e: Throwable) {
            SdlBridge.clearSdlInitialized()
            // Best effort. Failing here must not take down the launch - without SDL set up some
            // 26.3+ features will be degraded, but the game can still start. NOTE: this is the
            // ART side only. If this fails while the game still launches, LWJGL's own dlopen of
            // SDL3 will get an instance whose JNI was never set up, and SDL's Android backend
            // will crash during init - that is what CrashAnalyzer rule 22 decodes afterwards.
            Log.e(TAG, "SDL prepare failed - the game-side SDL3 instance will be uninitialized, " +
                "expect an SDL-init crash if the game reaches SDL_Init", e)
        }
    }

    /**
     * Tears down the ART-side SDL state after the JVM exits, so a later launch
     * in the same process starts clean. Posted to the UI thread (SDL state
     * touches Views) and fire-and-forget.
     */
    @JvmStatic
    fun resetAfterJvmExit(activity: Activity?) {
        if (activity == null || !SdlBridge.sdlEnabled) return
        activity.runOnUiThread {
            runCatching { SdlBridge.reset() }
                .onFailure { Log.w(TAG, "SDL reset failed", it) }
        }
    }
}
