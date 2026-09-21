package com.endiq.turtlelauncher.launch

import android.app.Activity
import android.os.Build
import android.system.Os
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

    /**
     * DroidBridge's Snapshot 4 workaround. RenderPearl prefers persistent
     * buffer storage when a wrapper advertises desktop GL extensions, but the
     * GLES-backed MobileGlues/Krypton path cannot safely map those buffers.
     * Hide only the two buffer-storage extensions for 26.3 pre/snapshot 4+
     * so RenderPearl uses its mutable-buffer fallback.
     *
     * This is process environment, not a game option: it must be installed
     * before the embedded JVM is forked and before SDL selects its GL library.
     */
    @JvmStatic
    fun applyDroidBridgeSnapshot4Patch(versionName: String?) {
        if (!isDroidBridgeSnapshot4OrLater(versionName)) return
        runCatching {
            Os.setenv(
                "MESA_EXTENSION_OVERRIDE",
                "-GL_ARB_buffer_storage -GL_EXT_buffer_storage",
                true
            )
            Os.setenv("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING", "1", true)
            Log.i(TAG, "DroidBridge Pre4: disabled persistent GL buffer storage for $versionName")
        }.onFailure {
            // The SDL/JNI fix below is independent of the renderer workaround.
            // Never make an otherwise launchable version fail just because an
            // OEM blocks process-environment writes.
            Log.w(TAG, "DroidBridge Pre4: could not apply buffer-storage override", it)
        }
    }

    @JvmStatic
    fun isDroidBridgeSnapshot4OrLater(versionName: String?): Boolean {
        if (versionName == null) return false
        val value = versionName.trim().lowercase()
            .replace('_', '-')
            .replace(' ', '-')
            .replace(Regex("-+"), "-")

        val snapshot = Regex("^26\\.3-(?:snapshot|pre)-?(\\d+)(?:$|[^0-9].*)").matchEntire(value)
        if (snapshot != null) return snapshot.groupValues[1].toIntOrNull()?.let { it >= 4 } == true

        // The 26.3 release and later 26.x releases retain the Snapshot 4
        // RenderPearl startup path.
        val release = Regex("^26\\.(\\d+)(?:$|[^0-9].*)").matchEntire(value)
        return release?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 3 } == true
    }

    /**
     * PowerVR Rogue drivers found on low-end Android devices can expose a
     * Vulkan loader but not Minecraft 26.3's required Vulkan 1.2 feature set.
     * Minecraft may otherwise fall back from its failed OpenGL window to that
     * unusable Vulkan backend. Force only this hardware family to OpenGL; do
     * not change the user's API preference on other GPUs or Minecraft versions.
     */
    @JvmStatic
    fun forcePowerVrOpenGl(versionName: String?, gameDir: File) {
        if (!isMinecraft26_3OrLater(versionName) || !isPowerVrDevice()) return

        val optionsFile = File(gameDir, "options.txt")
        if (!optionsFile.isFile) {
            Log.w(TAG, "PowerVR OpenGL guard: options.txt does not exist yet; Minecraft will create it")
            return
        }

        runCatching {
            val lines = optionsFile.readLines()
            var changed = false
            var foundPreference = false
            val rewritten = lines.map { line ->
                when {
                    line.startsWith("graphicsApiPreference:") -> {
                        foundPreference = true
                        if (line != "graphicsApiPreference:prefer_opengl") changed = true
                        "graphicsApiPreference:prefer_opengl"
                    }
                    line.startsWith("graphicsApi:") -> {
                        // Older 26.x snapshots used this key; preserve the
                        // key spelling if it is what this profile already has.
                        foundPreference = true
                        if (line != "graphicsApi:prefer_opengl") changed = true
                        "graphicsApi:prefer_opengl"
                    }
                    else -> line
                }
            }.toMutableList()
            if (!foundPreference) {
                rewritten += "graphicsApiPreference:prefer_opengl"
                changed = true
            }
            if (changed) {
                optionsFile.writeText(rewritten.joinToString("\n") + "\n")
                Log.w(TAG, "TurtleSDL3: PowerVR Vulkan capabilities are insufficient; forced Minecraft 26.3 to Prefer OpenGL")
            }
        }.onFailure {
            // A profile write failure must not turn into a launcher crash. The
            // valid Surface path remains independent and the game can still
            // report its own backend choice.
            Log.w(TAG, "PowerVR OpenGL guard: could not update ${optionsFile.absolutePath}", it)
        }
    }

    private fun isMinecraft26_3OrLater(versionName: String?): Boolean {
        val match = Regex("^26\\.(\\d+)(?:$|[^0-9].*)").matchEntire(versionName?.trim() ?: "") ?: return false
        return match.groupValues[1].toIntOrNull()?.let { it >= 3 } == true
    }

    private fun isPowerVrDevice(): Boolean {
        val buildIdentity = listOf(Build.HARDWARE, Build.BOARD, Build.DEVICE, Build.MANUFACTURER)
            .joinToString(" ").lowercase()
        return buildIdentity.contains("powervr") || buildIdentity.contains("rogue") ||
            buildIdentity.contains("sgx") || File("/vendor/lib64/libsrv_um.so").isFile ||
            File("/vendor/lib/libsrv_um.so").isFile
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
     * @param activity the Activity used as the SDL host. Must not be null.
     */
    @JvmStatic
    @Synchronized
    fun setup(activity: Activity?) {
        if (isActive) return
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

            // MinecraftGLSurface publishes its live Surface before realStart()
            // releases the launch callback. Carry that exact object into the SDL
            // host; passing null here used to replace it with the detached
            // SDLSurface holder, which is not a valid Android window.
            val gameSurface = SDLActivity.getTurtleNativeSurface()
            check(sdlSurface != null) {
                "SDL host Surface could not be created on the UI thread"
            }
            check(gameSurface != null && gameSurface.isValid()) {
                "No valid Minecraft Surface was published before SDL setup"
            }
            Log.i(TAG, "Using published Minecraft Surface for SDL (valid=true)")
            SDLActivity.externalInitialize(sdlSurface, null, gameSurface)
            SDL.setupJNI()

            // The real Minecraft view is not the SDLSurface view, so Android
            // will not deliver a callback to this detached host. Send the
            // initial dimensions once the JNI methods exist; later size and
            // destruction callbacks continue to come from MinecraftGLSurface.
            if (sdlSurface != null && gameSurface != null && gameSurface.isValid()) {
                val metrics = activity.resources.displayMetrics
                sdlSurface.surfaceChanged(null, 0, metrics.widthPixels, metrics.heightPixels)
            }

            // From here on MinecraftGLSurface forwards its own Surface callbacks to SDL's
            // SDLSurface, which is the only way SDL learns the real window size (see
            // notifySdlOfSurfaceSize()). isActive is the flag that switches that path on -
            // MinecraftGLSurface reads it from Java as SdlAndroidJniPrep.isActive().
            isActive = true
            Log.i(TAG, "SDL host state prepared")
        } catch (e: Throwable) {
            isActive = false
            // Do not let the guest JVM continue after ART-side SDL setup
            // failed. Its LWJGL dlopen would otherwise create an SDL3 instance
            // without the Android JNI state initialized above and turn this
            // recoverable setup error into a native crash.
            Log.e(TAG, "SDL prepare failed; refusing to launch an uninitialized game-side SDL3", e)
            throw IllegalStateException("SDL host setup failed; game launch was stopped safely", e)
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
