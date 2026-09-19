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
        @JvmStatic val renderer = StringSettingUnit("renderer", "4b4b8e4b-083d-429c-97e1-5e8239b6dc17")
        @JvmStatic val driver   = StringSettingUnit("driver", "Turnip")
        @JvmStatic val preferOpenGLBackend = BooleanSettingUnit("preferOpenGLBackend", true)
        @JvmStatic val lwjglCompatMode = StringSettingUnit("lwjglCompatMode", "auto")

        // ── Advanced Renderer Settings ────────────────────────────────────────────
        @JvmStatic val rendererShaderCacheEnabled = BooleanSettingUnit("rendererShaderCacheEnabled", true)
        /** Extra renderer-selection/env-var logging to the in-app log, for diagnosing a
         *  renderer that won't start. Not a rendering-internals debug layer - that would
         *  need support from the renderer libraries themselves, which this launcher can't add. */
        @JvmStatic val rendererDebugLogging = BooleanSettingUnit("rendererDebugLogging", false)
        /** LIBGL_BATCH: batches GL calls instead of issuing them one at a time. */
        @JvmStatic val jniBatching              = BooleanSettingUnit("jniBatching", true)
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
        @JvmStatic val adaptiveVsync            = BooleanSettingUnit("adaptive_vsync", false)
        @JvmStatic val lowLatencyFrontBuffer    = BooleanSettingUnit("low_latency_rendering", false)
        @JvmStatic val zinkPreferSystemDriver   = BooleanSettingUnit("zinkPreferSystemDriver", false)

        /** Auto Settings Optimizer: automatically tunes renderer/driver, RAM allocation, resolution scale, and FPS boost flags for this device at launch. */
        @JvmStatic val autoSettingsOptimizer    = BooleanSettingUnit("autoSettingsOptimizer", false)

        // ── Control ───────────────────────────────────────────────────────────
        @JvmStatic val disableGestures          = BooleanSettingUnit("disableGestures", true)
        @JvmStatic val disableDoubleTap         = BooleanSettingUnit("disableDoubleTap", false)
        @JvmStatic val controlSwitcherEnabled   = BooleanSettingUnit("controlSwitcherEnabled", true)
        @JvmStatic val timeLongPressTrigger     = IntSettingUnit("timeLongPressTrigger", 300)
        @JvmStatic val buttonScale              = IntSettingUnit("buttonscale", 100)
        @JvmStatic val buttonAllCaps            = BooleanSettingUnit("buttonAllCaps", false)
        @JvmStatic val mouseScale               = IntSettingUnit("mousescale", 100)
        @JvmStatic val mouseSpeed               = IntSettingUnit("mousespeed", 100)
        @JvmStatic val virtualMouseStart        = BooleanSettingUnit("mouse_start", true)
        @JvmStatic val customMouse              = StringSettingUnit("custom_mouse", "")

        // ── Mouse & Keyboard (ported from Zalith Launcher 2's control settings) ──
        /** Zalith2 physicalMouseMode: when ON a connected physical mouse stays a normal
         *  Android pointer (absolute hover positioning); when OFF the launcher grabs it via
         *  pointer capture for raw relative input, like a PC game does. Wired in
         *  AndroidPointerCapture/MinecraftGLSurface. */
        @JvmStatic val physicalMouseMode        = BooleanSettingUnit("physicalMouseMode", true)
        /** Zalith2 mouseCaptureSensitivity (25..300): extra multiplier on look movement
         *  while the game has the mouse grabbed (in-game processor + captured pointer). */
        @JvmStatic val mouseCaptureSensitivity  = IntSettingUnit("mouseCaptureSensitivity", 100)
        /** Zalith2 hideMouse: don't draw the virtual cursor overlay; movement and clicks
         *  keep working. Wired in Touchpad.onDraw. */
        @JvmStatic val hideMouse                = BooleanSettingUnit("hideMouse", false)
        /** Zalith2 gestureTapMouseAction: mouse button sent by the quick-tap gesture
         *  ("left"/"right"). Wired in RightClickGesture (the tap gesture). */
        @JvmStatic val gestureTapMouseAction    = StringSettingUnit("gestureTapMouseAction", "right")
        /** Zalith2 gestureLongPressMouseAction: mouse button held by the long-press
         *  gesture ("left"/"right"). Wired in LeftClickGesture (the long-press gesture). */
        @JvmStatic val gestureLongPressMouseAction = StringSettingUnit("gestureLongPressMouseAction", "left")
        /** Zalith2 physicalKeyImeCode: Android KeyEvent keycode of a hardware-keyboard key
         *  that toggles the on-screen keyboard in game; -1 = unbound. Wired in
         *  MinecraftGLSurface.processKeyEvent. */
        @JvmStatic val physicalKeyImeCode       = IntSettingUnit("physicalKeyImeCode", -1)
        /** TurtleLauncher Emotes: master toggle for the in-game menu's Emotes actions
         *  ("Play emote" wheel button + emote-website shortcut). Wired in MainActivity's
         *  MenuSettingsInitListener. */
        @JvmStatic val emotesEnabled            = BooleanSettingUnit("emotesEnabled", true)
        @JvmStatic val emoteWheelKeycode        = IntSettingUnit("emoteWheelKeycode", 66)
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
        @JvmStatic val awtCanvasSizeCap         = IntSettingUnit("awtCanvasSizeCap", 720)
        @JvmStatic val autoCleanupEnabled       = BooleanSettingUnit("autoCleanupEnabled", true)
        @JvmStatic val localSkinServerEnabled   = BooleanSettingUnit("localSkinServerEnabled", true)
        @JvmStatic val localSkinServerLanVisible = BooleanSettingUnit("localSkinServerLanVisible", false)
        @JvmStatic val lastAutoCleanupTime      = LongSettingUnit("lastAutoCleanupTime", 0L)
        @JvmStatic val gameMenuShowMemory       = BooleanSettingUnit("gameMenuShowMemory", false)
        @JvmStatic val gameMenuShowNativeMemory = BooleanSettingUnit("gameMenuShowNativeMemory", false)
        @JvmStatic val gameMenuShowFPS          = BooleanSettingUnit("gameMenuShowFPS", true)
        @JvmStatic val gameMenuMemoryText       = StringSettingUnit("gameMenuMemoryText", "M:")
        @JvmStatic val gameMenuLocation         = StringSettingUnit("gameMenuLocation", "center")
        @JvmStatic val gameMenuInfoRefreshRate  = IntSettingUnit("gameMenuInfoRefreshRate", 250)
        @JvmStatic val gameMenuAlpha            = IntSettingUnit("gameMenuAlpha", 100)
        @JvmStatic val hudAlpha                 = IntSettingUnit("hudAlpha", 100)
        @JvmStatic val pvpOverlayPreset         = BooleanSettingUnit("pvpOverlayPreset", false)
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
        @JvmStatic val animation                = BooleanSettingUnit("animation", true)
        @JvmStatic val animationSpeed           = IntSettingUnit("animationSpeed", 300)
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
        @JvmStatic val performanceHeatmapEnabled = BooleanSettingUnit("performanceHeatmapEnabled", true)
        @JvmStatic val backgroundServiceOptimization = BooleanSettingUnit("backgroundServiceOptimization", true)

        // ── Experimental ──────────────────────────────────────────────────────
        @JvmStatic val dumpShaders              = BooleanSettingUnit("dump_shaders", false)
        @JvmStatic val bigCoreAffinity          = BooleanSettingUnit("bigCoreAffinity", true)
        /** Fast Boot: skips file checksum/manifest verification and the pre-launch RAM check dialog to launch faster. */
        @JvmStatic val fastBoot                 = BooleanSettingUnit("fastBoot", true)

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
        @JvmStatic val schedulerTuning          = BooleanSettingUnit("schedulerTuning", true)

        // ── TurtleLauncher Phone Settings: Memory ────────────────────────────────
        @JvmStatic val autoRamCalculator        = BooleanSettingUnit("autoRamCalculator", true)
        /** -Xms = -Xmx (reserve the full heap up front - fewer heap-resize GC pauses, more
         *  up-front RAM commit). Off allocates -Xms at half of -Xmx instead. */
        @JvmStatic val equalHeapSizes           = BooleanSettingUnit("equalHeapSizes", true)
        /** Quick RAM presets shown in Phone Settings; "custom" defers entirely to the Game
         *  Settings slider / autoRamCalculator above. */
        @JvmStatic val ramPreset                = StringSettingUnit("ramPreset", "balanced")
        @JvmStatic val memoryPressureMonitor    = BooleanSettingUnit("memoryPressureMonitor", false)
        /** Adds -Xlog:gc (JDK unified logging) to the launch args and appends a running
         *  pause-count/total-pause-time summary to the launcher log on exit. */
        @JvmStatic val gcStatistics              = BooleanSettingUnit("gcStatistics", false)

        // ── TurtleLauncher FPS Boost ──────────────────────────────────────────
        /** Remove Minecraft's 300-FPS cap; adds -XX:+DisableExplicitGC */
        @JvmStatic val unlimitedFps             = BooleanSettingUnit("unlimitedFps", true)
        /** Low-latency rendering: JVM string/compile optimizations */
        @JvmStatic val lowLatencyRendering      = BooleanSettingUnit("lowLatencyRendering", true)
        /** LWJGL frame-pacing hints for smoother mobile GPU frame delivery */
        @JvmStatic val framePacing              = BooleanSettingUnit("framePacing", true)
        /** Drop frames when overloaded instead of queuing (reduces input lag) */
        @JvmStatic val frameSkipping            = BooleanSettingUnit("frameSkipping", false)
        /** Short GC pause target to avoid mid-frame GC stops */
        @JvmStatic val adaptiveFrameTiming      = BooleanSettingUnit("adaptiveFrameTiming", true)
        @JvmStatic val autoMemoryCleanup        = BooleanSettingUnit("autoMemoryCleanup", false)

        // ── TurtleLauncher Renderer/Driver Plugin Updater ───────────────────────
        /** Automatically check for renderer/driver plugin updates from upstream sources on launcher start. */
        @JvmStatic val autoCheckPluginUpdates   = BooleanSettingUnit("autoCheckPluginUpdates", true)
        /** Timestamp (ms) of the last renderer/driver plugin update check, used for the 5-minute cooldown. */
        @JvmStatic val lastPluginUpdateCheck    = LongSettingUnit("lastPluginUpdateCheck", 0L)
        /** TurtleLauncher Fast Boot: Minecraft version id Auto Settings Optimizer last ran picks for; skips re-running when unchanged. */
        @JvmStatic val lastOptimizedVersion     = StringSettingUnit("lastOptimizedVersion", "")
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
        @JvmStatic val smartWarmStart           = BooleanSettingUnit("smartWarmStart", true)
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
        @JvmStatic val highContrastMode         = BooleanSettingUnit("highContrastMode", false)
        @JvmStatic val fontFamily               = StringSettingUnit("fontFamily", "default")

        @JvmStatic val showRecordButtonHud      = BooleanSettingUnit("showRecordButtonHud", true)
        @JvmStatic val recordingFrameRate       = IntSettingUnit("recordingFrameRate", 30)
        @JvmStatic val recordingBitrateMbps     = IntSettingUnit("recordingBitrateMbps", 8)
        @JvmStatic val recordingResolutionScale = IntSettingUnit("recordingResolutionScale", 100)
        @JvmStatic val recordingMaxDurationMin  = IntSettingUnit("recordingMaxDurationMin", 0) // 0 = unlimited
        @JvmStatic val recordingShowTimer       = BooleanSettingUnit("recordingShowTimer", true)
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
        @JvmStatic val aiApiKey                 = StringSettingUnit("aiApiKey", "")
        @JvmStatic val aiModel                  = StringSettingUnit("aiModel", "gpt-4o-mini")
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
        @JvmStatic val enableTerracottaNodes    = BooleanSettingUnit("enableTerracottaNodes", false)
        @JvmStatic val terracottaNodes          = StringSettingUnit("terracottaNodes", "")
    }
}
