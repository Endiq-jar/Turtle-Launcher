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
 * THE CONFIRMED CRASH MECHANISM (decoded from the real tombstone + the bundled libSDL3.so
 * itself, Sept 2026): the observed SIGSEGV (pc=libSDL3.so+0xb94c4, si_addr=0x0) lands exactly
 * in this build's `Android_JNI_InitTouch` (function at +0xb94ac): it calls `Android_JNI_GetEnv`,
 * which reads SDL's `mJavaVM` static (+0x2bf350); when that is NULL it logs "SDL: Failed, there
 * is no JavaVM" and returns a NULL JNIEnv, and `Android_JNI_InitTouch` then dereferences it
 * without checking. `mJavaVM` is only ever set by SDL's own `JNI_OnLoad`. The log ALSO shows the
 * ART side initializing fine ("SDL host state prepared" from [setup] below), so the game thread
 * was calling into a SECOND libSDL3.so instance whose JNI_OnLoad never ran.
 *
 * Why a second instance can exist at all: plain `dlopen()` (which is how LWJGL's
 * `DynamicLinkLoader.ndlopen` loads SDL3 - it never goes through System.loadLibrary, so
 * JNI_OnLoad is never run for it) does NOT re-run JNI_OnLoad, but bionic's linker deduplicates
 * dlopens of the SAME FILE by (st_dev, st_ino) within a linker-namespace chain
 * (linker.cpp find_loaded_library_by_inode), which would hand LWJGL the instance [setup] had
 * already initialized. A split therefore requires one of:
 *  (a) a DIFFERENT libSDL3.so file being found first - the per-version natives cache dir is
 *      FIRST on java.library.path (LaunchArgs.resolveNativeLibraryPath), and Minecraft's own
 *      natives bootstrap extracts libraries there from the classpath at startup;
 *  (b) OEM linker-namespace divergence - the crash device is an OPPO/ColorOS phone whose log
 *      shows a burst of "not accessible for the namespace" vendor-library dlopen failures
 *      around game start, consistent with a non-stock namespace topology for game-classified
 *      processes that can defeat the same-inode dedupe. (Those vendor-lib messages themselves
 *      are harmless: liblwjgl.so's DT_NEEDED is only libdl/libc, verified - they're OEM
 *      game-injection attempts, not missing dependencies of ours.)
 * (a) is fixable from Java, (b) is not - see [ensureSingleSdl3Source] for (a) plus the
 * -Dorg.lwjgl.sdl.libname pinning in LaunchArgs, which together force LWJGL to dlopen the
 * exact APK file [setup] initialized, making the inode dedupe apply wherever the namespace
 * topology allows it.
 *
 * STATUS: the previously *confirmed* abort (jar/.so `nativeSetupJNI` signature mismatch) stays
 * fixed. The original SIGSEGV this class exists to prevent is NOT confirmed fixed - this
 * environment has no device or emulator to test on, and the native half of Amethyst's fix is
 * the part that could not be ported. The two Java-side defense layers above are
 * mechanistically grounded but unverified on a real device; CrashAnalyzer's rule 22 tells the
 * user the same thing when this path crashes, and the "TurtleSDL3:"/"SdlAndroidJniPrep" log
 * lines below are what a next crash report needs to settle it.
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
     * TurtleLauncher CRASH FIX (MC 26.3+ SDL3), defense layer 1 of 2 - the second is the
     * `-Dorg.lwjgl.sdl.libname=<APK path>` JVM arg LaunchArgs now adds. Called from
     * JREUtils.launchJavaVM for SDL versions, before [setup] and before the JVM starts.
     *
     * What this does and why (see the class doc for the full two-instance analysis):
     *  1. Deletes any `libSDL3*.so` sitting in the per-version natives cache dir. That dir is
     *     FIRST on java.library.path (LaunchArgs.resolveNativeLibraryPath), so a libSDL3.so
     *     there - e.g. extracted by Minecraft's own natives bootstrap from a classpath jar -
     *     would shadow the launcher's APK-installed Android SDL3 build. A shadow copy is a
     *     DIFFERENT file, so bionic's same-inode dlopen dedupe can't unify it with the
     *     instance [setup] initializes: LWJGL would dlopen an uninitialized (and possibly
     *     desktop-build) SDL3 whose mJavaVM is NULL - exactly the confirmed +0xb94c4 crash.
     *     The dir is a launcher-owned cache; the game re-extracts whatever it actually needs
     *     at runtime, and we only ever touch libSDL3* - never the launcher's own natives,
     *     which live in PathManager.DIR_NATIVE_LIB, not here.
     *  2. Logs the SDL3 selection state (which file LWJGL should end up on, its size, and
     *     whether it really is an Android SDL3 build) so a crash report shows what was
     *     loaded without needing the tombstone to prove it. The Android-build probe reads the
     *     .so once and looks for "SDL_GetAndroidJNIEnv" - a symbol only SDL's Android backend
     *     has; a desktop-linux SDL3 (which some Mojang natives jars could contain) would fail
     *     this probe and be named in the log, which alone would explain any subsequent crash.
     *
     * Both layers are best-effort (no device here to verify on); each logs enough to confirm
     * or refute itself from a real crash report.
     */
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
            // 26.3+ features will be degraded, but the game can still start. NOTE: this is the
            // ART side only. If this fails while the game still launches, LWJGL's own dlopen of
            // SDL3 will get an instance whose mJavaVM/mActivityClass never got set, and SDL's
            // Android backend will crash inside Android_JNI_InitTouch (see class doc) - that is
            // exactly what CrashAnalyzer rule 22 decodes for the user afterwards.
            Log.e(TAG, "SDL prepare failed - the game-side SDL3 instance will be uninitialized, " +
                "expect the sdl3_android_init_sigsegv crash if the game reaches SDL_Init", e)
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
