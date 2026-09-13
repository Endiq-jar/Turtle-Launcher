package com.endiq.turtlelauncher.setting

import com.endiq.turtlelauncher.context.ContextExecutor
import com.endiq.turtlelauncher.setting.unit.BooleanSettingUnit
import com.endiq.turtlelauncher.setting.unit.IntSettingUnit
import com.endiq.turtlelauncher.setting.unit.LongSettingUnit
import com.endiq.turtlelauncher.setting.unit.StringSettingUnit
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences


class AllSettings {
    companion object {
        // ── Video ──────────────────────────────────────────────────────────────
        /** Which renderer a launch uses, stored as the renderer's getUniqueIdentifier() UUID
         *  (Renderers.setCurrentRenderer looks it up by that, never by name/id).
         *
         *  TurtleLauncher: the default was Holy GL4ES ("f7e985d8-6d4c-f63c-d9f1-06074dab823a"),
         *  now MobileGlues ("4b4b8e4b-083d-429c-97e1-5e8239b6dc17" - both UUIDs verified
         *  against renderer/renderers/HolyGL4ESRenderer.kt and MobileGluesRenderer.kt).
         *  MobileGlues is the better out-of-the-box pick here: it's a full GL implementation
         *  on top of the device's own GLES 3.x rather than a GL->GLES translator, its bundled
         *  libmobileglues.so is self-contained (see MobileGluesRenderer's doc comment), and
         *  it's the one GL-family renderer RendererCatalog marks as shader-pack capable.
         *  It only ever applies to a fresh install / a player who never touched the setting -
         *  an existing value is always kept, and Renderers.setCurrentRenderer() still falls
         *  back to the first *compatible* renderer when this one isn't available on the
         *  device (e.g. a missing libmobileglues.so for that ABI), so nothing can end up
         *  pointing at a renderer that can't load. */
        @JvmStatic val renderer = StringSettingUnit("renderer", "4b4b8e4b-083d-429c-97e1-5e8239b6dc17")
        @JvmStatic val driver   = StringSettingUnit("driver", "Turnip")
        /** TurtleLauncher: backs the single Video-settings Graphics Backend switch (replaces
         *  the old two-button OpenGL/Vulkan quick-select). ON = OpenGL (HolyGL4ES), OFF =
         *  Vulkan (Zink). Still just a shortcut over [renderer] underneath - this only
         *  records which side of the switch is currently shown, VideoSettingsFragment
         *  re-syncs it against the real active renderer every time the screen opens so it
         *  can never silently drift from what's actually running. */
        @JvmStatic val preferOpenGLBackend = BooleanSettingUnit("preferOpenGLBackend", true)
        /** TurtleLauncher: "Add Multiple LWJGL versions" (Compatibility Improvements, item
         *  16). This launcher only has two real bundled LWJGL natives to choose between
         *  (liblwjgl.so - newer build matched to org.lwjgl:lwjgl-sdl/MC 26.3+, and
         *  liblwjgl-legacy.so - the older pre-SDL build) - "auto" picks between them by
         *  inspecting each version's own manifest (Tools.versionUsesLwjglSdl), same as
         *  before this setting existed. "new"/"legacy" force one side regardless of what
         *  the manifest says, for the case a version's manifest doesn't accurately reflect
         *  which native it actually needs. See Tools.resolveLwjglMode(). */
        @JvmStatic val lwjglCompatMode = StringSettingUnit("lwjglCompatMode", "auto")

        // ── Advanced Renderer Settings ────────────────────────────────────────────
        /** Off = skip setting MESA_GLSL_CACHE_DIR entirely (Zink/VirGL/VGPU/Freedreno only
         *  - GL4ES-family renderers don't use it either way). Recompiles shaders from
         *  scratch every launch instead of reusing yesterday's compiled cache; the only
         *  reason to turn it off is suspecting a corrupted cache is itself causing issues. */
        @JvmStatic val rendererShaderCacheEnabled = BooleanSettingUnit("rendererShaderCacheEnabled", true)
        /** Extra renderer-selection/env-var logging to the in-app log, for diagnosing a
         *  renderer that won't start. Not a rendering-internals debug layer - that would
         *  need support from the renderer libraries themselves, which this launcher can't add. */
        @JvmStatic val rendererDebugLogging = BooleanSettingUnit("rendererDebugLogging", false)
        // ── TurtleLauncher JNI Optimization (Phone Settings) ────────────────────
        // GL4ES-family renderers (Holy GL4ES only now - Krypton Wrapper was removed as a
        // built-in) - replaces the old single gl4esPerformanceTweaks toggle with four
        // granular switches over the same real libgl4es env vars (all four confirmed
        // present via `strings` on the actual bundled libgl4es_114/115.so). No equivalent
        // for LTW/MobileGlues/Zink/VirGL/VGPU/Freedreno since those never touch libgl4es.
        /** LIBGL_BATCH: batches GL calls instead of issuing them one at a time. */
        @JvmStatic val jniBatching              = BooleanSettingUnit("jniBatching", true)
        /** LIBGL_USEVBO: routes vertex data through VBOs instead of re-uploading client-side
         *  arrays every call - i.e. caches the buffer reference GPU-side instead of re-sending it. */
        @JvmStatic val jniCachedReferences       = BooleanSettingUnit("jniCachedReferences", true)
        /** LIBGL_RECYCLEFBO: reuses framebuffer objects instead of allocating/freeing new ones. */
        @JvmStatic val nativeObjectPooling       = BooleanSettingUnit("nativeObjectPooling", true)
        /** LIBGL_SKIPTEXCOPIES: skips a redundant texture copy libgl4es otherwise does on
         *  certain upload paths, cutting the number of native calls made per frame. */
        @JvmStatic val reducedJniCalls           = BooleanSettingUnit("reducedJniCalls", true)

        @JvmStatic val ignoreNotch              = BooleanSettingUnit("ignoreNotch", true)
        @JvmStatic val ignoreNotchLauncher      = BooleanSettingUnit("ignoreNotchLauncher", true)
        @JvmStatic val resolutionRatio          = IntSettingUnit("resolutionRatio", 100)
        @JvmStatic val sustainedPerformance     = BooleanSettingUnit("sustainedPerformance", false)
        @JvmStatic val alternateSurface         = BooleanSettingUnit("alternate_surface", false)
        @JvmStatic val forceVsync               = BooleanSettingUnit("force_vsync", false)
        @JvmStatic val vsyncInZink              = BooleanSettingUnit("vsync_in_zink", false)
        /** Requests adaptive vsync (EGL_EXT_swap_control_tear) instead of a flat on/off - only
         *  takes effect while vsync is otherwise on, and only on drivers that advertise the
         *  extension; silently falls back to regular vsync elsewhere. See gl_swap_interval(). */
        @JvmStatic val adaptiveVsync            = BooleanSettingUnit("adaptive_vsync", false)
        /** Front-buffer / low-latency rendering (EGL_KHR_mutable_render_buffer +
         *  EGL_ANDROID_front_buffer_auto_refresh where available). Trades a small amount of
         *  tearing risk for lower input latency by skipping the swap-chain wait. See
         *  apply_low_latency_mode() in gl_bridge.c.
         *  TurtleLauncher bugfix: was also named lowLatencyRendering, colliding with the
         *  unrelated FPS Boost setting further down this file (JVM string/compile
         *  optimizations - see its own doc comment) - two @JvmStatic vals with the same name
         *  in the same companion object is a hard "Conflicting declarations" compile error,
         *  and every AllSettings.lowLatencyRendering call site in the project became an
         *  unresolvable "Overload resolution ambiguity" as a result. Renamed to
         *  lowLatencyFrontBuffer to disambiguate; the persisted key ("low_latency_rendering")
         *  is unchanged so this doesn't reset anyone's existing saved preference. Exposed as
         *  a switch in VideoSettingsFragment, next to Adaptive V-Sync - see that switch's own
         *  bugfix comment for the View-Binding id-collision half of this same bug. */
        @JvmStatic val lowLatencyFrontBuffer    = BooleanSettingUnit("low_latency_rendering", false)
        @JvmStatic val zinkPreferSystemDriver   = BooleanSettingUnit("zinkPreferSystemDriver", false)

        /** Auto Settings Optimizer: automatically tunes renderer/driver, RAM allocation, resolution scale, and FPS boost flags for this device at launch. */
        @JvmStatic val autoSettingsOptimizer    = BooleanSettingUnit("autoSettingsOptimizer", false)

        // ── Control ───────────────────────────────────────────────────────────
        @JvmStatic val disableGestures          = BooleanSettingUnit("disableGestures", true)
        @JvmStatic val disableDoubleTap         = BooleanSettingUnit("disableDoubleTap", false)
        /** TurtleLauncher: shows a small on-screen button while playing (icon = the keyboard's
         *  Tab key, ic_keyboard_tab) that swaps to the next control layout on tap - same effect
         *  as in-game menu -> Control -> "Replace controls", but without opening the drawer.
         *  Toggled from the in-game launcher menu (Control tab) as "Control Switcher"; when off
         *  the button is hidden. Cycling order is the alphabetical order of the .json files in
         *  PathManager.DIR_CTRLMAP_PATH, wrapping back to the first after the last. */
        @JvmStatic val controlSwitcherEnabled   = BooleanSettingUnit("controlSwitcherEnabled", true)
        @JvmStatic val timeLongPressTrigger     = IntSettingUnit("timeLongPressTrigger", 300)
        @JvmStatic val buttonScale              = IntSettingUnit("buttonscale", 100)
        @JvmStatic val buttonAllCaps            = BooleanSettingUnit("buttonAllCaps", false)
        @JvmStatic val mouseScale               = IntSettingUnit("mousescale", 100)
        @JvmStatic val mouseSpeed               = IntSettingUnit("mousespeed", 100)
        @JvmStatic val virtualMouseStart        = BooleanSettingUnit("mouse_start", true)
        @JvmStatic val customMouse              = StringSettingUnit("custom_mouse", "")
        /** Optional. CurseForge's v1 API requires a key (issued to registered apps by
         *  Overwolf/CurseForge Core) to resolve a mod's actual download URL from its
         *  project/file ID - there's no keyless path anymore, the old addons-ecs.forgesvc.net
         *  workaround third-party launchers used to use was shut down. Left blank, CurseForge
         *  modpack imports still install overrides + the correct mod loader, they just can't
         *  auto-download the mods themselves - see CurseForgeModPackInstallHelper. */
        @JvmStatic val curseForgeApiKey         = StringSettingUnit("curseforge_api_key", "")
        @JvmStatic val enableGyro               = BooleanSettingUnit("enableGyro", false)
        @JvmStatic val gyroSensitivity          = IntSettingUnit("gyroSensitivity", 100)
        @JvmStatic val gyroSampleRate           = IntSettingUnit("gyroSampleRate", 16)
        @JvmStatic val gyroSmoothing            = BooleanSettingUnit("gyroSmoothing", true)
        @JvmStatic val gyroInvertX              = BooleanSettingUnit("gyroInvertX", false)
        @JvmStatic val gyroInvertY              = BooleanSettingUnit("gyroInvertY", false)
        @JvmStatic val deadZoneScale            = IntSettingUnit("gamepad_deadzone_scale", 100)

        // ── Game ──────────────────────────────────────────────────────────────
        @JvmStatic val versionIsolation         = BooleanSettingUnit("versionIsolation", true)
        @JvmStatic val versionCustomInfo        = StringSettingUnit("versionCustomInfo", "TurtleLauncher")
        @JvmStatic val autoSetGameLanguage      = BooleanSettingUnit("autoSetGameLanguage", true)
        @JvmStatic val gameLanguageOverridden   = BooleanSettingUnit("gameLanguageOverridden", false)
        @JvmStatic val setGameLanguage          = StringSettingUnit("setGameLanguage", "system")
        @JvmStatic val selectRuntimeMode        = StringSettingUnit("selectRuntimeMode", "auto")
        @JvmStatic val javaArgs = StringSettingUnit(
            "javaArgs",
            "-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:G1HeapRegionSize=16M " +
            "-XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20"
        )
        @JvmStatic val ramAllocation = lazy {
            IntSettingUnit("allocation", LauncherPreferences.findBestRAMAllocation(ContextExecutor.getApplication()))
        }
        @JvmStatic val javaSandbox              = BooleanSettingUnit("java_sandbox", true)
        /** TurtleLauncher: caps the virtual "screen" reported to Caciocavallo/AWT for the Java
         *  GUI installer window (Forge/Cleanroom/etc installers, see AWTCanvasView). Root cause
         *  of "the installer window is tiny": that virtual canvas used to be 80% of the actual
         *  device resolution (e.g. ~1920x864 on a 2400x1080 phone), but a Swing installer window
         *  is a small FIXED pixel size (~500-600px, decided by its own layout, not by how big
         *  the reported screen is) - so it only ever filled a small corner of that huge canvas,
         *  and AWTCanvasView then scales the *entire* canvas (window plus all the black space
         *  around it) up to fill the phone screen. Capping the canvas near a typical installer's
         *  own size makes that same window fill most of the canvas instead, so it fills most of
         *  the phone screen once scaled up. Tunable rather than hardcoded since different jars
         *  (not just Forge-family installers) may want more room. */
        @JvmStatic val awtCanvasSizeCap         = IntSettingUnit("awtCanvasSizeCap", 720)
        /** TurtleLauncher: unattended, age-based deletion of old crash reports/launcher logs/
         *  stale native-extraction temp dirs (see AutoCleanup.kt) - distinct from the manual
         *  full-wipe CleanUpCache.kt already does from Settings. */
        @JvmStatic val autoCleanupEnabled       = BooleanSettingUnit("autoCleanupEnabled", true)
        // TurtleLauncher: local skin/cape system (see TurtleSkinServer) - lets local/offline
        // accounts' custom skins actually show up in-game (client-side always; server-side
        // too if the LAN toggle below is on and whoever runs the multiplayer server points
        // their own authlib-injector at this device's LAN address).
        @JvmStatic val localSkinServerEnabled   = BooleanSettingUnit("localSkinServerEnabled", true)
        @JvmStatic val localSkinServerLanVisible = BooleanSettingUnit("localSkinServerLanVisible", false)
        /** Epoch millis of the last automatic cleanup run - rate-limits AutoCleanup to once a
         *  day so it doesn't re-scan every version's crash-reports folder on every single launch. */
        @JvmStatic val lastAutoCleanupTime      = LongSettingUnit("lastAutoCleanupTime", 0L)
        @JvmStatic val gameMenuShowMemory       = BooleanSettingUnit("gameMenuShowMemory", false)
        /** Appends process native heap (android.os.Debug) to the "M:" HUD line. Only visible
         *  when gameMenuShowMemory is also on, since it extends that same line rather than
         *  adding a second HUD element. */
        @JvmStatic val gameMenuShowNativeMemory = BooleanSettingUnit("gameMenuShowNativeMemory", false)
        @JvmStatic val gameMenuShowFPS          = BooleanSettingUnit("gameMenuShowFPS", true)
        @JvmStatic val gameMenuMemoryText       = StringSettingUnit("gameMenuMemoryText", "M:")
        @JvmStatic val gameMenuLocation         = StringSettingUnit("gameMenuLocation", "center")
        @JvmStatic val gameMenuInfoRefreshRate  = IntSettingUnit("gameMenuInfoRefreshRate", 250)
        @JvmStatic val gameMenuAlpha            = IntSettingUnit("gameMenuAlpha", 100)
        /** TurtleLauncher: opacity of the in-game info HUD (FPS/CPS/Keystrokes/etc window), independent of the menu button's own alpha. */
        @JvmStatic val hudAlpha                 = IntSettingUnit("hudAlpha", 100)
        /** TurtleLauncher: one-tap preset that toggles CPS + Keystrokes + Mousestrokes together for PvP. */
        @JvmStatic val pvpOverlayPreset         = BooleanSettingUnit("pvpOverlayPreset", false)
        /** TurtleLauncher: static pre-launch scan for mods whose Mixin configs both target the same class. */
        @JvmStatic val modConflictDetection     = BooleanSettingUnit("modConflictDetection", true)

        // ── Shizuku (privileged ADB/root integration) ──────────────────────────
        /** Master switch for the whole Shizuku/Sui integration (see feature/shizuku).
         *  When off, every entry point falls back to the unprivileged path as if Shizuku
         *  were not installed at all. Safe to leave on: nothing here needs Shizuku to work. */
        @JvmStatic val shizukuEnabled = BooleanSettingUnit("shizukuEnabled", true)
        /** Re-apply the privileged keep-alive tweaks (phantom-process limit, process
         *  priority) automatically at each game launch, not just when the button is pressed. */
        @JvmStatic val shizukuAutoPerformance = BooleanSettingUnit("shizukuAutoPerformance", true)

        // ── Launcher ──────────────────────────────────────────────────────────
        @JvmStatic val checkLibraries           = BooleanSettingUnit("checkLibraries", true)
        @JvmStatic val verifyManifest           = BooleanSettingUnit("verifyManifest", true)
        @JvmStatic val resourceImageCache       = BooleanSettingUnit("resourceImageCache", true)
        @JvmStatic val addFullResourceName      = BooleanSettingUnit("addFullResourceName", true)
        @JvmStatic val downloadSource           = StringSettingUnit("downloadSource", "default")
        @JvmStatic val maxDownloadThreads       = IntSettingUnit("maxDownloadThreads", 128)
        @JvmStatic val launcherTheme            = StringSettingUnit("launcherTheme", "dark")
        /** Master switch for every screen/panel transition.
         *
         *  TurtleLauncher: was false. The launcher's whole point is the modern UI, and with
         *  this off by default none of the transition work was ever visible - the pickers
         *  below did nothing until you found and enabled this first. Now on; there is a
         *  speed slider right under it and transitions are skipped entirely while a game
         *  session is running (see TurtleTransitions.isEnabled), so low-end devices can
         *  still turn it straight back off with one tap. */
        @JvmStatic val animation                = BooleanSettingUnit("animation", true)
        @JvmStatic val animationSpeed           = IntSettingUnit("animationSpeed", 300)
        /** Screen enter animation - see com.endiq.turtlelauncher.utils.anim.EnterTransition.
         *  Applied to every screen transition and every show/hide that goes through
         *  TurtleTransitions, so it's genuinely app-wide rather than per-fragment. */
        @JvmStatic val animationEnter           = StringSettingUnit("animationEnter", "slide_up")
        /** Screen exit animation - see com.endiq.turtlelauncher.utils.anim.ExitTransition. */
        @JvmStatic val animationExit            = StringSettingUnit("animationExit", "slide_up")
        @JvmStatic val pageOpacity              = IntSettingUnit("pageOpacity", 100)
        @JvmStatic val enableLogOutput          = BooleanSettingUnit("enableLogOutput", false)
        @JvmStatic val quitLauncher             = BooleanSettingUnit("quitLauncher", true)
        @JvmStatic val acceptPreReleaseUpdates  = BooleanSettingUnit("acceptPreReleaseUpdates", false)

        // ── TurtleLauncher Mod Auto-Maintenance ─────────────────────────────────
        /** Automatically fetch & install a mod's missing mandatory dependencies from Modrinth before launch. */
        @JvmStatic val autoDependencyInstall    = BooleanSettingUnit("autoDependencyInstall", true)
        /** Automatically check installed mods for newer Modrinth versions before launch and offer to update them. */
        @JvmStatic val autoModUpdateCheck       = BooleanSettingUnit("autoModUpdateCheck", true)
        // TurtleLauncher: Performance Heatmap (Section 11) - static-analysis 🟢/🟡/🔴 tier
        // badge on mods/resourcepacks/shaderpacks in the Files browser. See
        // HeatmapAnalyzer's doc comment for exactly what "performance" means here.
        @JvmStatic val performanceHeatmapEnabled = BooleanSettingUnit("performanceHeatmapEnabled", true)
        /** TurtleLauncher: "Background Services" (item 20) - while a game session is
         *  active, pauses fragment-transition animations, the passive plugin-update
         *  network check, and AssetPrefetcher's indexing pass, plus trims Glide's memory
         *  cache once right before launch. Everything it touches self-restores once the
         *  session ends (see BackgroundServiceManager's class doc) - this toggle exists as
         *  an escape hatch in case any of that ever interacts badly with a specific device. */
        @JvmStatic val backgroundServiceOptimization = BooleanSettingUnit("backgroundServiceOptimization", true)

        // ── Experimental ──────────────────────────────────────────────────────
        @JvmStatic val dumpShaders              = BooleanSettingUnit("dump_shaders", false)
        @JvmStatic val bigCoreAffinity          = BooleanSettingUnit("bigCoreAffinity", true)
        /** Fast Boot: skips file checksum/manifest verification and the pre-launch RAM check dialog to launch faster. */
        @JvmStatic val fastBoot                 = BooleanSettingUnit("fastBoot", false)

        // ── TurtleLauncher Phone Settings: CPU ───────────────────────────────────
        /** Detect this device's core count via Runtime.availableProcessors() each launch
         *  (clamped to 1-16, falling back to 8 if the platform reports something bogus)
         *  and pass it through -XX:ActiveProcessorCount. Off only makes sense alongside
         *  manualCoreOverride. */
        @JvmStatic val autoDetectCores          = BooleanSettingUnit("autoDetectCores", true)
        /** Overrides autoDetectCores globally with manualCoreCount. */
        @JvmStatic val manualCoreOverride       = BooleanSettingUnit("manualCoreOverride", false)
        /** Core count used when manualCoreOverride (or a per-instance override) is active. */
        @JvmStatic val manualCoreCount          = IntSettingUnit("manualCoreCount", 8)
        /** When on, JREUtils checks the current version's VersionConfig.getCpuCoreOverride()
         *  (set from the version's own config editor) before falling back to the global
         *  manual/auto pick above. -1 on a version means "follow global". */
        @JvmStatic val perInstanceCpuOverride   = BooleanSettingUnit("perInstanceCpuOverride", false)
        /** Reveals the Thread Affinity / Scheduler Tuning switches below in Phone Settings. */
        @JvmStatic val advancedCpuTuning        = BooleanSettingUnit("advancedCpuTuning", false)
        /** Hints the native side (POJAV_SCHED_TUNING) to lower the JVM/render threads' nice
         *  value for steadier frame delivery under CPU contention, same env-var-handoff
         *  pattern as bigCoreAffinity's POJAV_BIG_CORE_AFFINITY above. */
        @JvmStatic val schedulerTuning          = BooleanSettingUnit("schedulerTuning", false)

        // ── TurtleLauncher Phone Settings: Memory ────────────────────────────────
        /** Auto RAM Calculator: keep ramAllocation pinned to findBestRAMAllocation()'s pick
         *  for this device instead of a value the user dragged the slider to. Distinct from
         *  AutoSettingsOptimizer's own RAM tuning, which only runs at launch per-version;
         *  this one is the persistent global default the Game Settings slider starts from. */
        @JvmStatic val autoRamCalculator        = BooleanSettingUnit("autoRamCalculator", true)
        /** -Xms = -Xmx (reserve the full heap up front - fewer heap-resize GC pauses, more
         *  up-front RAM commit). Off allocates -Xms at half of -Xmx instead. */
        @JvmStatic val equalHeapSizes           = BooleanSettingUnit("equalHeapSizes", true)
        /** Quick RAM presets shown in Phone Settings; "custom" defers entirely to the Game
         *  Settings slider / autoRamCalculator above. */
        @JvmStatic val ramPreset                = StringSettingUnit("ramPreset", "balanced")
        /** Periodically checks ActivityManager.MemoryInfo and warns (log + optional toast)
         *  when the device is under memory pressure, so a player can see it coming before
         *  the OS kills the game process outright. See MemoryPressureMonitor. */
        @JvmStatic val memoryPressureMonitor    = BooleanSettingUnit("memoryPressureMonitor", false)
        /** Adds -Xlog:gc (JDK unified logging) to the launch args and appends a running
         *  pause-count/total-pause-time summary to the launcher log on exit. */
        @JvmStatic val gcStatistics              = BooleanSettingUnit("gcStatistics", false)

        // ── TurtleLauncher FPS Boost ──────────────────────────────────────────
        /** Remove Minecraft's 300-FPS cap; adds -XX:+DisableExplicitGC */
        @JvmStatic val unlimitedFps             = BooleanSettingUnit("unlimitedFps", false)
        /** Low-latency rendering: JVM string/compile optimizations */
        @JvmStatic val lowLatencyRendering      = BooleanSettingUnit("lowLatencyRendering", false)
        /** LWJGL frame-pacing hints for smoother mobile GPU frame delivery */
        @JvmStatic val framePacing              = BooleanSettingUnit("framePacing", false)
        /** Drop frames when overloaded instead of queuing (reduces input lag) */
        @JvmStatic val frameSkipping            = BooleanSettingUnit("frameSkipping", false)
        /** Short GC pause target to avoid mid-frame GC stops */
        @JvmStatic val adaptiveFrameTiming      = BooleanSettingUnit("adaptiveFrameTiming", false)
        /** Periodically trigger a G1 GC during idle stretches (menus, paused, low activity)
         *  to release memory back to the OS instead of holding onto peak usage indefinitely.
         *  Off by default since it's a real GC pause, just a scheduled/idle one rather than
         *  an unpredictable one - worth trying on devices that get sluggish over long sessions. */
        @JvmStatic val autoMemoryCleanup        = BooleanSettingUnit("autoMemoryCleanup", false)

        // ── TurtleLauncher Renderer/Driver Plugin Updater ───────────────────────
        /** Automatically check for renderer/driver plugin updates from upstream sources on launcher start. */
        @JvmStatic val autoCheckPluginUpdates   = BooleanSettingUnit("autoCheckPluginUpdates", true)
        /** Timestamp (ms) of the last renderer/driver plugin update check, used for the 5-minute cooldown. */
        @JvmStatic val lastPluginUpdateCheck    = LongSettingUnit("lastPluginUpdateCheck", 0L)
        /** TurtleLauncher Fast Boot: Minecraft version id Auto Settings Optimizer last ran picks for; skips re-running when unchanged. */
        @JvmStatic val lastOptimizedVersion     = StringSettingUnit("lastOptimizedVersion", "")
        /** TurtleLauncher: RAM value (MB) Auto Settings Optimizer last wrote to ramAllocation, used to detect a manual user change so it isn't clobbered on the next launch. -1 = never run. */
        @JvmStatic val lastAutoRamAllocation    = IntSettingUnit("lastAutoRamAllocation", -1)

        // ── TurtleLauncher In-Game HUD Modules ───────────────────────────────────
        /** Clicks-per-second counter, tracked from the virtual left mouse button (attack). */
        @JvmStatic val showCpsHud                = BooleanSettingUnit("showCpsHud", true)
        /** Live WASD + Space key-press indicator. */
        @JvmStatic val showKeystrokesHud         = BooleanSettingUnit("showKeystrokesHud", true)
        /** Live left/right mouse-button-press indicator. */
        @JvmStatic val showMousestrokesHud       = BooleanSettingUnit("showMousestrokesHud", true)
        /** Session stopwatch — elapsed time since the current game session started. */
        @JvmStatic val showStopwatchHud          = BooleanSettingUnit("showStopwatchHud", false)
        /** Cumulative playtime across all sessions. */
        @JvmStatic val showPlaytimeHud           = BooleanSettingUnit("showPlaytimeHud", false)
        /** Battery percentage readout. */
        @JvmStatic val showSystemResourcesHud    = BooleanSettingUnit("showSystemResourcesHud", false)
        /** Real-world wall-clock time readout. */
        @JvmStatic val showTimeHud               = BooleanSettingUnit("showTimeHud", false)
        /** Persisted cumulative playtime in milliseconds, across all sessions. */
        @JvmStatic val totalPlaytimeMs           = LongSettingUnit("totalPlaytimeMs", 0L)
        /** Per-day playtime buckets (JSON, "yyyy-MM-dd" -> ms) backing the home screen's weekly chart. */
        @JvmStatic val dailyPlaytimeJson         = StringSettingUnit("dailyPlaytimeJson", "")

        // ── Other ─────────────────────────────────────────────────────────────
        @JvmStatic val tcVibrateDuration        = IntSettingUnit("tcVibrateDuration", 100)
        @JvmStatic val currentAccount           = StringSettingUnit("currentAccount", "")
        @JvmStatic val launcherProfile          = StringSettingUnit("launcherProfile", "default")
        @JvmStatic val defaultCtrl              = StringSettingUnit("defaultCtrl", PathManager.FILE_CTRLDEF_FILE)
        @JvmStatic val defaultRuntime           = StringSettingUnit("defaultRuntime", "")
        @JvmStatic val notificationPermissionRequest       = BooleanSettingUnit("notification_permission_request", false)
        @JvmStatic val skipNotificationPermissionCheck     = BooleanSettingUnit("skipNotificationPermissionCheck", false)
        @JvmStatic val localAccountReminders    = BooleanSettingUnit("localAccountReminders", true)
        @JvmStatic val updateCheck              = LongSettingUnit("updateCheck", 0L)
        @JvmStatic val ignoreUpdate             = StringSettingUnit("ignoreUpdate", "")
        @JvmStatic val noticeCheck              = LongSettingUnit("noticeCheck", 0L)
        @JvmStatic val noticeNumbering          = IntSettingUnit("noticeNumbering", 0)
        @JvmStatic val noticeDefault            = BooleanSettingUnit("noticeDefault", false)

        /**
         * TurtleLauncher: Last version for which What's New dialog was shown.
         * Accessed from Java via AllSettings.getWhatsNewShownVersion()
         * (Kotlin @JvmStatic generates a static getter, accessible as a property in Kotlin
         * and as a static method in Java).
         */
        @JvmStatic val whatsNewShownVersion     = StringSettingUnit("whatsNewShownVersion", "")

        @JvmStatic val buttonSnapping           = BooleanSettingUnit("buttonSnapping", true)
        @JvmStatic val buttonSnappingDistance   = IntSettingUnit("buttonSnappingDistance", 8)
        @JvmStatic val hotbarType               = StringSettingUnit("hotbarType", "auto")
        @JvmStatic val hotbarWidth = lazy {
            IntSettingUnit("hotbarWidth", Tools.currentDisplayMetrics.widthPixels / 3)
        }
        @JvmStatic val hotbarHeight = lazy {
            IntSettingUnit("hotbarHeight", Tools.currentDisplayMetrics.heightPixels / 4)
        }

        // ── TurtleLauncher v10 ────────────────────────────────────────────────
        // HUD / performance
        @JvmStatic val backgroundAssetPrefetch  = BooleanSettingUnit("backgroundAssetPrefetch", true)
        // TurtleLauncher: Smart Launcher Warm Start (Section 12) - while sitting on the
        // main menu, best-effort warms the renderer/JRE native libs and account token
        // for whatever version is currently selected. See LauncherWarmStart's doc
        // comment for the real, architecture-honest scope of each piece.
        @JvmStatic val smartWarmStart           = BooleanSettingUnit("smartWarmStart", true)
        // TurtleLauncher: check github.com/Endiq-jar/TurtleLauncher/releases on launch and
        // prompt if a newer version is out. Best-effort - see UpdateChecker.
        @JvmStatic val autoCheckForUpdates      = BooleanSettingUnit("autoCheckForUpdates", true)
        @JvmStatic val hudModuleScale           = IntSettingUnit("hudModuleScale", 100)
        @JvmStatic val showRamGraphHud          = BooleanSettingUnit("showRamGraphHud", false)
        @JvmStatic val showPingHud              = BooleanSettingUnit("showPingHud", false)
        @JvmStatic val showScreenshotButtonHud  = BooleanSettingUnit("showScreenshotButtonHud", true)
        @JvmStatic val hudModulePositions       = StringSettingUnit("hudModulePositions", "{}")

        // Mods
        @JvmStatic val forgeConflictDetection   = BooleanSettingUnit("forgeConflictDetection", true)

        // Settings / UX
        @JvmStatic val settingsSearchHistory    = StringSettingUnit("settingsSearchHistory", "")
        @JvmStatic val resolutionAutoDetect     = BooleanSettingUnit("resolutionAutoDetect", false)
        @JvmStatic val compactMode              = BooleanSettingUnit("compactMode", false)
        @JvmStatic val leftHandedMode           = BooleanSettingUnit("leftHandedMode", false)
        @JvmStatic val fontScale                = IntSettingUnit("fontScale", 100)
        // TurtleLauncher: Accessibility (roadmap item 22). fontScale above already existed but
        // was dead - defined, never read by anything - until AccessibilityHelper.wrapContext()
        // started consuming it. highContrastMode/fontFamily are new alongside it. All three are
        // wired up in AccessibilitySettingsFragment.
        @JvmStatic val highContrastMode         = BooleanSettingUnit("highContrastMode", false)
        @JvmStatic val fontFamily               = StringSettingUnit("fontFamily", "default")

        // TurtleLauncher: in-game screen recording (roadmap item 22). See ScreenRecorder for
        // the capture engine and RecordingSettingsFragment for the Settings -> Recording screen
        // these back.
        @JvmStatic val showRecordButtonHud      = BooleanSettingUnit("showRecordButtonHud", true)
        @JvmStatic val recordingFrameRate       = IntSettingUnit("recordingFrameRate", 30)
        @JvmStatic val recordingBitrateMbps     = IntSettingUnit("recordingBitrateMbps", 8)
        @JvmStatic val recordingResolutionScale = IntSettingUnit("recordingResolutionScale", 100)
        @JvmStatic val recordingMaxDurationMin  = IntSettingUnit("recordingMaxDurationMin", 0) // 0 = unlimited
        @JvmStatic val recordingShowTimer       = BooleanSettingUnit("recordingShowTimer", true)
        // TurtleLauncher: capture in-game audio alongside video (see ScreenRecorder's class doc
        // for how - AudioPlaybackCaptureConfiguration via a MediaProjection consent prompt, not
        // the microphone). Default on; turning this off skips the consent prompt entirely and
        // records video-only, same as before this existed.
        @JvmStatic val recordingCaptureAudio    = BooleanSettingUnit("recordingCaptureAudio", true)
        @JvmStatic val customBackgroundPath     = StringSettingUnit("customBackgroundPath", "")
        @JvmStatic val customBackgroundIsVideo  = BooleanSettingUnit("customBackgroundIsVideo", false)
        @JvmStatic val iconPackPath             = StringSettingUnit("iconPackPath", "")

        // Diagnostics
        @JvmStatic val offlineModeFallback      = BooleanSettingUnit("offlineModeFallback", true)
        @JvmStatic val crashHistoryList         = StringSettingUnit("crashHistoryList", "[]")
        @JvmStatic val customCrashRules         = StringSettingUnit("customCrashRules", "[]")
        @JvmStatic val anrDetectorEnabled       = BooleanSettingUnit("anrDetectorEnabled", true)
        @JvmStatic val anrTimeoutMs             = IntSettingUnit("anrTimeoutMs", 5000)
        @JvmStatic val logRegexFilterHistory    = StringSettingUnit("logRegexFilterHistory", "")

        // AI-assisted crash diagnosis: only used as a fallback when no local
        // CrashAnalyzer rule (including custom rules) recognises the crash, and only
        // if the user has supplied their own API key. Nothing is sent anywhere unless
        // both of those are true.
        @JvmStatic val aiCrashHelpEnabled       = BooleanSettingUnit("aiCrashHelpEnabled", false)
        // TurtleLauncher: was a hardcoded default key - Google's secret-scanning flags any
        // real API key committed to a public repo as leaked and revokes it (confirmed:
        // "API key was reported as leaked" from Gemini itself), so a replacement key would
        // fail the exact same way the moment it's pushed. Left blank -
        // AiCrashAdvisor already handles an empty key by prompting the user to add their own
        // in Settings rather than silently failing. Renamed from aiCrashHelpApiKey/
        // aiCrashHelpModel now that the provider is OpenAI's Chat Completions API, not
        // Gemini - model names change over time on any provider, so this stays a setting
        // rather than a hardcoded constant, same as before.
        @JvmStatic val aiApiKey                 = StringSettingUnit("aiApiKey", "")
        @JvmStatic val aiModel                  = StringSettingUnit("aiModel", "gpt-4o-mini")
        // TurtleLauncher: separate opt-in from aiCrashHelpEnabled above, deliberately - this one
        // sends actual skin/cape texture images (not just a text crash log) to whatever
        // third-party endpoint aiApiKey/aiModel point at, and each classification is a real
        // per-image API call/cost, so it shouldn't piggyback on the crash-help toggle's consent.
        // Reuses the same aiApiKey/aiModel as crash help, since both assume an OpenAI-compatible
        // Chat Completions endpoint. See AiContentModerator for what "appropriate" means here.
        @JvmStatic val aiSkinFilterEnabled       = BooleanSettingUnit("aiSkinFilterEnabled", false)

        // ── TurtleLauncher built-in AI Assistant (top bar → Assistant) ────────
        /** The Assistant screen itself runs entirely on-device - it's a local
         *  knowledge base + live-launcher-state reader (see feature/ai/TurtleAssistant.kt),
         *  so unlike aiCrashHelpEnabled/aiSkinFilterEnabled above it needs no API key,
         *  no account and no network at all. This toggle only hides/shows the top-bar
         *  entry point, for players who don't want the button there. */
        @JvmStatic val aiAssistantEnabled        = BooleanSettingUnit("aiAssistantEnabled", true)
        /** Keep the Assistant's conversation across launcher restarts (JSON file under
         *  the app's private files dir - see feature/ai/AssistantHistory.kt). Off = every
         *  session starts from a clean transcript. */
        @JvmStatic val aiAssistantHistoryEnabled = BooleanSettingUnit("aiAssistantHistoryEnabled", true)

        // Custom DNS resolver for the launcher's own network requests (downloads/API
        // calls), independent of the download-source (BMCLAPI) mirror above.
        @JvmStatic val dnsServer                = StringSettingUnit("dnsServer", "cloudflare")

        // ── Terracotta (Friends/LAN) ──────────────────────────────────────────
        /** Ported from TurtleLauncher PR #1496 (closes ZL2#1486/#1211 - the exact
         *  "Cannot find scaffolding server" / PingHostFail join failure this fork's own
         *  TerracottaFragment already surfaces a one-time toast about, see
         *  terracotta_join_known_issue). The default public EasyTier rendezvous/relay
         *  node is unreliable for guests; letting the user point at their own EasyTier
         *  server node bypasses it entirely instead of waiting on upstream's shared
         *  infra. Off by default - empty/disabled falls straight through to the
         *  existing default node behavior, nothing changes unless the user opts in. */
        @JvmStatic val enableTerracottaNodes    = BooleanSettingUnit("enableTerracottaNodes", false)
        /** Custom EasyTier server node URI (e.g. tcp://your.server:11010). Ignored unless
         *  [enableTerracottaNodes] is also on; blank falls back to the default node list
         *  even when the toggle is on, same as upstream's "keep the default node logic when empty". */
        @JvmStatic val terracottaNodes          = StringSettingUnit("terracottaNodes", "")
    }
}
