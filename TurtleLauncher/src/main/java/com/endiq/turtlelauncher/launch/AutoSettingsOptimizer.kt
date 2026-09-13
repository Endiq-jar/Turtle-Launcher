package com.endiq.turtlelauncher.launch

import android.content.Context
import android.opengl.EGL14
import android.os.Build
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.renderer.renderers.FreedrenoRenderer
import com.endiq.turtlelauncher.renderer.renderers.MobileGluesRenderer
import com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.platform.BatterySaverManager
import com.endiq.turtlelauncher.utils.platform.ThermalManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences

/**
 * AutoSettingsOptimizer (replaces the old AutoGraphicsOptimizer)
 *
 * Tunes more than just renderer/driver: also sets a sensible RAM
 * allocation, live resolution scale, and the TurtleLauncher FPS Boost flags
 * for the device's tier, all before launch. Enabled by default — anything
 * it sets can still be overridden by hand in Video/Experimental settings
 * (it just re-applies its own picks on the next launch unless turned off).
 *
 * Renderer/driver selection maps detected GPU vendor to one of the built-in renderers
 * (see renderer/renderers/ and RendererCatalog):
 *   Adreno GPU     → Freedreno, driver = Turnip (if Vulkan available)
 *   Mali/ARM GPU   → Zink if Vulkan available, else MobileGlues
 *   PowerVR/other  → MobileGlues, driver = default
 *   Unknown        → renderer left as-is (i.e. the AllSettings.renderer default, which is
 *                    also MobileGlues - see that setting's doc comment)
 *
 * TurtleLauncher: the two GL-family picks above used to be LTW (Mali) and Holy GL4ES
 * (PowerVR/other). Both are now MobileGlues so this optimizer agrees with the launcher's
 * own default renderer instead of quietly overriding it with a different one on the first
 * launch - a player who never opens Video settings would otherwise see "MobileGlues" as the
 * documented default and "Holy GL4ES" as what's actually running. The Adreno→Freedreno and
 * Vulkan→Zink picks are unchanged: those are device-specific wins, not defaults.
 *
 * Special case (MC 26.1+): MC 26.x's CubeMapTexture panorama background
 * calls glTexImage2D with GL_TEXTURE_CUBE_MAP + GL_RGB8, which OpenGL ES
 * 3.2 doesn't support as a renderable cubemap format — causing a crash
 * (GL_INVALID_ENUM, error 1280) on PowerVR/Apple/unknown GPUs. For MC 26.1+
 * on those GPUs we switch to Zink if Vulkan is available, which
 * avoids the ES cubemap limitation entirely.
 *
 * Device tiers (by total RAM) drive everything else:
 *   LOW  (< 3GB)   → RAM alloc from findBestRAMAllocation, 80% resolution scale,
 *                    frame skipping on (prioritize responsiveness over smoothness),
 *                    other FPS boost flags left off.
 *   MID  (3–6GB)   → full resolution, adaptive frame timing on, FPS boost flags
 *                    otherwise left off (no need, no obvious win on mid-tier silicon).
 *   HIGH (> 6GB)   → full resolution, all FPS boost flags on (unlimited FPS,
 *                    low-latency rendering, frame pacing, adaptive frame timing).
 *
 * A device already thermally throttled (API 29+, see [ThermalManager]) or with
 * Android's own Battery Saver on (see [BatterySaverManager]) at launch time gets
 * capped one profile down from what its RAM tier alone would pick; a severely
 * throttled device gets forced onto a conservative profile outright. RAM
 * allocation itself is untouched by this - only the FPS-boost/resolution flags
 * that actually drive extra heat and power draw.
 */
object AutoSettingsOptimizer {
    private const val TAG = "AutoSettingsOptimizer"

    private const val LOW_TIER_RAM_MB = 3072
    private const val HIGH_TIER_RAM_MB = 6144

    /**
     * @param context       Activity context
     * @param mcVersionId   Minecraft version string, e.g. "26.1.2" (empty = legacy path)
     */
    fun apply(context: Context, mcVersionId: String = "") {
        // Fast Boot only skips the RAM/FPS-boost performance-tier math (applyPerformanceTier) -
        // that's the part actually worth skipping for boot speed. GPU/renderer detection
        // (applyGraphics) always re-runs regardless of Fast Boot: it's a cheap throwaway EGL
        // pbuffer probe, and skipping it let a bad renderer pick (e.g. Krypton Wrapper
        // wrongly matched by the old broad "arm" substring check against a weak GPU-string
        // fallback) get cached via lastOptimizedVersion and then *never* re-evaluated for
        // that Minecraft version again - even after the detection logic was fixed, a device
        // that got mis-picked once would keep crashing on every subsequent launch forever.
        applyGraphics(context, mcVersionId)
        if (AllSettings.fastBoot.getValue() && mcVersionId.isNotEmpty() &&
            AllSettings.lastOptimizedVersion.getValue() == mcVersionId) {
            Logging.i(TAG, "Fast Boot: skipping performance-tier re-tune (already applied for $mcVersionId)")
            return
        }
        applyPerformanceTier(context)
        if (mcVersionId.isNotEmpty()) {
            AllSettings.lastOptimizedVersion.put(mcVersionId).save()
        }
    }

    private fun applyGraphics(context: Context, mcVersionId: String) {
        val gpu = detectGpu()
        val isMC26Plus = LaunchArgs.isMinecraftVersionAtLeast(mcVersionId, 26, 1, 0)
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) context.display
            else (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        val refreshRate = display?.refreshRate?.toInt() ?: 60
        val metrics = context.resources.displayMetrics
        val cpuCores = Runtime.getRuntime().availableProcessors()
        // Queried via ActivityManager, not a live GL context (none exists yet at this point -
        // the game hasn't started) - the standard, correct way to get this ahead of time.
        val glEsVersionPacked = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val glEsVersion = "${glEsVersionPacked shr 16}.${glEsVersionPacked and 0xffff}"
        Logging.i(TAG, "Detected GPU: $gpu | Manufacturer: ${Build.MANUFACTURER} | Android ${Build.VERSION.SDK_INT} | " +
                "MC26+: $isMC26Plus | Refresh rate: ${refreshRate}Hz | Resolution: ${metrics.widthPixels}x${metrics.heightPixels} | " +
                "CPU cores: $cpuCores | OpenGL ES: $glEsVersion")

        val hasVulkan = Tools.checkVulkanSupport(context.packageManager)

        // TurtleLauncher: this used to store short strings like "gl4es114"/"opengles3" into
        // AllSettings.renderer, which Renderers.setCurrentRenderer looks up by UUID
        // (getUniqueIdentifier()) - those never matched anything, so this picker silently
        // always fell back to the first renderer in the list regardless of what was
        // "selected" here. Fixed to store the real UUID of one of the six FCL-sourced
        // renderers (see the renderer/renderers/ package).
        val (rendererUuid, driver) = when {
            gpu.contains("adreno", ignoreCase = true) -> {
                // Freedreno is explicitly "optimized primarily for Qualcomm Adreno GPUs"
                // (see FreedrenoRenderer's doc comment) - a better match than a generic
                // GL4ES build now that it's an actual available option.
                val driverChoice = if (hasVulkan) "Turnip" else "default"
                Pair(FreedrenoRenderer().getUniqueIdentifier(), driverChoice)
            }
            gpu.contains("mali", ignoreCase = true) -> {
                if (hasVulkan) {
                    Pair(ZinkRenderer().getUniqueIdentifier(), "default")
                } else {
                    // MobileGlues, not LTW: matches the launcher default (see class doc).
                    Pair(MobileGluesRenderer().getUniqueIdentifier(), "default")
                }
            }
            gpu.contains("powervr", ignoreCase = true) ||
            gpu.contains("sgx", ignoreCase = true) ||
            gpu.contains("apple", ignoreCase = true) -> {
                if (isMC26Plus && hasVulkan) {
                    Logging.i(TAG, "MC 26.1+ on PowerVR/Apple GPU: switching to Zink to avoid CubeMap GL_INVALID_ENUM crash")
                    Pair(ZinkRenderer().getUniqueIdentifier(), "default")
                } else {
                    // MobileGlues, not Holy GL4ES: matches the launcher default (see class
                    // doc). Holy GL4ES also has a documented 1.21.4 ceiling in
                    // RendererCatalog, so it was the wrong standing pick for new versions.
                    Pair(MobileGluesRenderer().getUniqueIdentifier(), "default")
                }
            }
            else -> {
                if (isMC26Plus && hasVulkan) {
                    Logging.i(TAG, "MC 26.1+ unknown GPU: attempting Zink to avoid CubeMap GL_INVALID_ENUM crash")
                    Pair(ZinkRenderer().getUniqueIdentifier(), "default")
                } else {
                    Logging.i(TAG, "Unknown GPU, leaving renderer/driver unchanged")
                    return
                }
            }
        }

        Logging.i(TAG, "Auto-selected renderer=$rendererUuid driver=$driver")
        AllSettings.renderer.put(rendererUuid).save()
        AllSettings.driver.put(driver).save()
    }

    private fun applyPerformanceTier(context: Context) {
        val totalRamMb = Tools.getTotalDeviceMemory(context)

        // TurtleLauncher Phone Settings: Auto RAM Calculator toggle gates this whole block -
        // if the player turned it off (wants their Game Settings slider value left alone
        // entirely, not even nudged by the "don't clobber a manual change" heuristic below),
        // skip RAM auto-tuning outright rather than just falling into the "user already
        // changed it" branch, since that branch still overwrites on a fresh install (lastAutoRam == -1).
        val appliedRam: Int
        if (!AllSettings.autoRamCalculator.getValue()) {
            appliedRam = AllSettings.ramAllocation.value.getValue()
            Logging.i(TAG, "Auto RAM Calculator disabled in Phone Settings: leaving RAM at ${appliedRam}MB")
        } else {
            val bestRam = presetAdjustedRam(context, LauncherPreferences.findBestRAMAllocation(context))

            // Only overwrite RAM allocation if the user hasn't manually changed it since
            // we last set it (lastAutoRam == -1 means we've never applied a pick yet on
            // this install). Otherwise every launch silently reverted a manual override
            // back to our computed value.
            val currentRam = AllSettings.ramAllocation.value.getValue()
            val lastAutoRam = AllSettings.lastAutoRamAllocation.getValue()
            if (lastAutoRam == -1 || currentRam == lastAutoRam) {
                AllSettings.ramAllocation.value.put(bestRam).save()
                AllSettings.lastAutoRamAllocation.put(bestRam).save()
                appliedRam = bestRam
            } else {
                Logging.i(TAG, "Skipping RAM auto-tune: user manually set ${currentRam}MB (last auto pick was ${lastAutoRam}MB)")
                appliedRam = currentRam
            }
        }

        val powerSaving = BatterySaverManager.isPowerSaveMode(context)
        val throttled = ThermalManager.isThrottled(context) || powerSaving
        val severelyThrottled = ThermalManager.isSeverelyThrottled(context)
        if (throttled) {
            Logging.i(TAG, "Capping FPS-boost profile regardless of RAM tier (thermal severe=$severelyThrottled, batterySaver=$powerSaving)")
        }

        when {
            severelyThrottled -> {
                Logging.i(TAG, "Severe thermal throttling: forcing conservative profile, RAM=${appliedRam}MB")
                AllSettings.resolutionRatio.put(70).save()
                AllSettings.frameSkipping.put(true).save()
                AllSettings.unlimitedFps.put(false).save()
                AllSettings.lowLatencyRendering.put(false).save()
                AllSettings.framePacing.put(false).save()
                AllSettings.adaptiveFrameTiming.put(true).save()
            }
            totalRamMb < LOW_TIER_RAM_MB -> {
                Logging.i(TAG, "Low-tier device (${totalRamMb}MB RAM): 80% resolution, frame skipping on, RAM=${appliedRam}MB")
                AllSettings.resolutionRatio.put(80).save()
                AllSettings.frameSkipping.put(true).save()
                AllSettings.unlimitedFps.put(false).save()
                AllSettings.lowLatencyRendering.put(false).save()
                AllSettings.framePacing.put(false).save()
                AllSettings.adaptiveFrameTiming.put(false).save()
            }
            totalRamMb < HIGH_TIER_RAM_MB || throttled -> {
                Logging.i(TAG, "Mid-tier profile (${totalRamMb}MB RAM, capped=$throttled): full resolution, adaptive frame timing on, RAM=${appliedRam}MB")
                AllSettings.resolutionRatio.put(100).save()
                AllSettings.frameSkipping.put(false).save()
                AllSettings.unlimitedFps.put(false).save()
                AllSettings.lowLatencyRendering.put(false).save()
                AllSettings.framePacing.put(false).save()
                AllSettings.adaptiveFrameTiming.put(true).save()
            }
            else -> {
                Logging.i(TAG, "High-tier device (${totalRamMb}MB RAM): full FPS Boost, RAM=${appliedRam}MB")
                AllSettings.resolutionRatio.put(100).save()
                AllSettings.frameSkipping.put(false).save()
                AllSettings.unlimitedFps.put(true).save()
                AllSettings.lowLatencyRendering.put(true).save()
                AllSettings.framePacing.put(true).save()
                AllSettings.adaptiveFrameTiming.put(true).save()
            }
        }
    }

    /** TurtleLauncher Phone Settings: RAM Presets. "balanced" (the default) is just
     *  findBestRAMAllocation()'s own pick, untouched - that function's tiers already are
     *  the balanced recommendation. "low"/"high" scale it down/up from there; "custom"
     *  passes the baseline through unchanged since Auto RAM Calculator being on with
     *  preset=custom is a contradiction in terms that shouldn't normally happen (the
     *  Phone Settings UI defers to the Game Settings slider for "custom" instead of
     *  calling into this optimizer at all), but a harmless no-op is the safe fallback. */
    private fun presetAdjustedRam(context: Context, baseline: Int): Int {
        val totalRamMb = Tools.getTotalDeviceMemory(context)
        return when (AllSettings.ramPreset.getValue()) {
            "low" -> (baseline * 0.7).toInt().coerceAtLeast(384)
            "high" -> (baseline * 1.4).toInt().coerceAtMost((totalRamMb * 0.75).toInt()).coerceAtLeast(baseline)
            else -> baseline // "balanced" or "custom"
        }
    }

    private fun detectGpu(): String {
        // A real, throwaway GLES context is the only source here that reliably returns the
        // actual GPU chip name ("PowerVR Rogue GM9446", "Adreno 660", "Mali-G78", etc.) via
        // GL_RENDERER. The fallback signals below (EGL_VENDOR, the ro.hardware.egl system
        // property, Build.HARDWARE/SoC codename) frequently do NOT contain the GPU brand at
        // all - e.g. PowerVR GPUs report EGL_VENDOR as "Imagination Technologies", not
        // "PowerVR", so a `contains("powervr")` check against those alone silently misses
        // real PowerVR devices and falls through to "unknown GPU", leaving whatever renderer
        // was already selected instead of the PowerVR-specific picks below (see the header
        // doc's cubemap-crash note - and a real Krypton Wrapper SIGSEGV seen on a PowerVR
        // Rogue device in an uploaded crash log, same likely root cause: that device was
        // probably never actually recognized as PowerVR by the old string matching).
        queryGlRenderer()?.let { return it }
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, null, 0, null, 0)
            val vendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR) ?: ""
            val renderer = System.getProperty("ro.hardware.egl") ?: ""
            val gpuFromBuild = Build.HARDWARE
            "$vendor $renderer $gpuFromBuild"
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    /** Creates a throwaway 1x1 pbuffer-backed GLES2 context purely to read GL_RENDERER (and
     *  GL_VENDOR alongside it), then tears the context down immediately. Returns null if
     *  anything about this fails (driver not ready this early, locked-down device, etc.) so
     *  the caller falls back to the weaker signals instead of detection itself crashing. */
    private fun queryGlRenderer(): String? {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) return null
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            val vendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR)
            if (renderer.isNullOrBlank()) null else "$vendor $renderer"
        } catch (e: Exception) {
            Logging.w(TAG, "GL_RENDERER probe failed, falling back to weaker GPU signals: ${e.message}")
            null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            }
        }
    }
}
