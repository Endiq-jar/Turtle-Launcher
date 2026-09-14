package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TurtleLauncher Crash & Log Analyzer.
 *
 * Looks at the launcher's "latestlog.txt" (and, if available, Minecraft's own
 * crash-report file) and tries to recognise known failure signatures, surfacing a
 * plain-language cause + a concrete checklist of fixes instead of a raw stack trace.
 *
 * This is intentionally *not* a generic log-parsing framework: it is a small,
 * hand-maintained knowledge base of patterns we've actually seen cause real crashes
 * on Android (native library loading, renderer driver bugs, OOM, broken/incompatible
 * mods, corrupted downloads, auth issues...). New patterns can be added to [rules]
 * as they're discovered.
 *
 * Used from two places:
 *  - After the game process exits with a non-zero code (a real "crash"), via
 *    [analyzeGameExit], wired into JREUtils → ErrorActivity.
 *  - While the game process is still alive but appears stuck (a "black screen" that
 *    never crashes), via [analyzeFrozenState], wired into [GameWatchdog].
 */
object CrashAnalyzer {

    enum class Severity { CRITICAL, WARNING, INFO }

    /**
     * A concrete, in-app action Crash Analyzer 2.0 can perform on the user's behalf for a
     * given [Diagnosis] instead of just describing what to do — the "one-click repair" part
     * of the fix list. Every type below is backed by a real operation in [executeRepair];
     * there's no repair action here that isn't actually implemented.
     */
    enum class RepairActionType {
        /** Deletes the writable per-version natives cache dir so it gets rebuilt clean. */
        CLEAR_NATIVES_CACHE,
        /** Clears this version's renderer override, falling back to the launcher default. */
        RESET_RENDERER_OVERRIDE,
        /** Clears the app's external cache dir (safe to wipe, gets recreated on demand). */
        CLEAR_APP_CACHE,
        /** Flips Settings → Experimental → Fast Boot off. */
        DISABLE_FAST_BOOT,
        /** Deletes one specific file identified from the log (a corrupted jar/config/etc). */
        DELETE_FILE,
        /** Lowers Settings → Game → RAM Allocation by one step. */
        LOWER_RAM_ALLOCATION,
        /** Raises Settings → Game → RAM Allocation by one step. */
        INCREASE_RAM_ALLOCATION,
        /** Self-Healing Launcher: resets file permissions on the natives cache, mods,
         *  config, and pinned-Java-runtime folders (the actual spots an Android storage
         *  permission glitch tends to hit — not a blind recursive chmod of the whole
         *  game folder, which would be slow and pointless on files the app already owns). */
        FIX_PERMISSIONS,
        /** Self-Healing Launcher: deletes this version's config-like files (options.txt,
         *  servers.dat, launcher_profiles.json, optionsof.txt) if they're zero-byte or
         *  fail a basic parse check, so Minecraft regenerates clean defaults next launch. */
        RESTORE_CONFIGS,
        /** Self-Healing Launcher: re-runs the same hash-verified client-jar/library/asset
         *  downloader the normal launch path uses, so anything missing or corrupted gets
         *  re-fetched. */
        VERIFY_GAME_FILES,
        /** Self-Healing Launcher: if this version's pinned Java runtime is missing or
         *  broken on disk, removes it and clears the pin so the next launch re-provisions
         *  a working runtime automatically via TurtleJREAutoInstaller. */
        REPAIR_RUNTIME,
        /** Flips Settings → Video → Performance → "Native object pooling" off, i.e.
         *  AllSettings.nativeObjectPooling → LIBGL_RECYCLEFBO=0 on next launch. */
        DISABLE_FBO_RECYCLING
    }

    data class RepairAction(
        val type: RepairActionType,
        val label: String,
        /** Absolute path this action operates on, only used by [RepairActionType.DELETE_FILE]. */
        val targetPath: String? = null
    )

    data class RepairResult(val success: Boolean, val message: String)

    data class Diagnosis(
        val title: String,
        val cause: String,
        val fixSteps: List<String>,
        val severity: Severity = Severity.CRITICAL,
        /** Zero or more repairs [executeRepair] can actually perform for this diagnosis. */
        val repairActions: List<RepairAction> = emptyList()
    )

    private class Rule(
        val title: String,
        val matches: (text: String) -> Boolean,
        val diagnosis: (text: String) -> Diagnosis
    )

    private fun fixed(
        title: String,
        cause: String,
        fixSteps: List<String>,
        severity: Severity = Severity.CRITICAL,
        repairActions: List<RepairAction> = emptyList()
    ): (String) -> Diagnosis =
        { Diagnosis(title, cause, fixSteps, severity, repairActions) }

    private fun has(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it, ignoreCase = true) }

    // ── Knowledge base ───────────────────────────────────────────────────────
    // Order matters: more specific rules are checked first so generic ones don't
    // "steal" a match that already has a much better explanation.
    private val rules: List<Rule> by lazy {
        listOf(
            // 1. Native renderer library failed to load (liblwjgl.so / libpojavexec.so).
            // This was the root cause behind every crash log we've analysed so far —
            // Mojang's own version JSON re-asserts an incomplete java.library.path
            // *after* the launcher sets the correct one. Fixed in LaunchArgs.kt, but
            // kept here in case an older/custom build still hits it.
            Rule(
                title = "native_library_path",
                matches = { has(it, "Failed to locate library:", "no pojavexec in java.library.path", "no pojavexec_awt in java.library.path") },
                diagnosis = fixed(
                    "Native renderer libraries failed to load (liblwjgl.so / libpojavexec.so)",
                    "The JVM could not find the launcher's bundled native libraries on java.library.path. " +
                        "This happens when Minecraft's own version JSON re-asserts an incomplete native library " +
                        "path JVM argument after the launcher sets the correct (full) one — since the JVM applies " +
                        "-D system properties in order and the last one for a given key wins, the incomplete one " +
                        "silently takes over and the game can never find liblwjgl.so / libpojavexec.so / libopenal.so.",
                    listOf(
                        "Update to the latest TurtleLauncher build — this exact bug is fixed in LaunchArgs.kt (the native library path is now re-asserted last, after Minecraft's own JVM args, so it can't be overridden).",
                        "If it still happens after updating: clear the natives cache for this version below, then relaunch.",
                        "Make sure the APK was installed as the universal/arm64 build and not a stripped split APK with native libraries removed."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.CLEAR_NATIVES_CACHE, "Clear natives cache"))
                )
            ),
            // 1b. pojavInitOpenGL crashes at pc=0x0 when POJAV_RENDERER doesn't match one
            // of libpojavexec.so's own hardcoded recognized strings ("opengles",
            // "custom_gallium", "vulkan_zink", "gallium_freedreno", "gallium_panfrost",
            // "gallium_virgl" - confirmed by disassembly, see Renderers.kt's top-of-file
            // doc comment). Fixed for all six built-in renderers via
            // RendererInterface.getNativeRendererId(); kept here because a renderer
            // plugin (RendererPluginManager) could still declare a POJAV_RENDERER value
            // that isn't one of those six and hit this exact crash.
            Rule(
                title = "turtle_renderer_unrecognized",
                matches = { has(it, "pojavInitOpenGL") && has(it, "SIGSEGV") },
                diagnosis = fixed(
                    "Renderer's POJAV_RENDERER value isn't recognized by the native launcher (pojavInitOpenGL crash)",
                    "libpojavexec.so only recognizes a fixed set of legacy POJAV_RENDERER strings. An " +
                        "unrecognized value falls through its whole comparison chain into an unguarded call " +
                        "through an uninitialized function pointer, crashing at pc=0x0 on every launch, " +
                        "regardless of device or GPU.",
                    listOf(
                        "If you're on a built-in renderer (Holy GL4ES, LTW, MobileGlues, VirGL, Zink, Freedreno, VGPU), update to the latest TurtleLauncher build — this is fixed in RendererInterface.getNativeRendererId().",
                        "If you're on a renderer plugin: check the plugin's declared POJAV_RENDERER value against the six libpojavexec.so recognizes (opengles, custom_gallium, vulkan_zink, gallium_freedreno, gallium_panfrost, gallium_virgl) and report a mismatch to the plugin's author.",
                        "As an immediate workaround, reset this version's renderer override below, or pick a different one manually in Settings → Video → Renderer."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.RESET_RENDERER_OVERRIDE, "Reset renderer override"))
                )
            ),
            // 2b. Krypton Wrapper's native GL4ES backend (libng_gl4es.so) SIGSEGV - seen in an
            // uploaded log on a PowerVR Rogue GPU, back when Krypton Wrapper was still a
            // built-in renderer. It was removed as a built-in for exactly this crash (replaced
            // with LTW/MobileGlues in Renderers.kt) but can still be reached through an
            // external renderer plugin (RendererPluginManager still recognizes Krypton's own
            // plugin package, com.bzlzhh.plugin.ngg) - kept as diagnosis/redirection for that
            // path. No native source for this prebuilt library to patch either way.
            Rule(
                title = "krypton_libng_gl4es_sigsegv",
                matches = { has(it, "libng_gl4es.so") && has(it, "SIGSEGV") },
                diagnosis = fixed(
                    "Krypton Wrapper's native GL4ES backend crashed (libng_gl4es.so)",
                    "This is a native SIGSEGV inside libng_gl4es.so itself, which backs the Krypton Wrapper renderer " +
                        "plugin. It's a prebuilt upstream binary with no native source in this project to patch - " +
                        "seen so far on PowerVR Rogue GPUs, may affect other GPUs too.",
                    listOf(
                        "Switch to a built-in renderer instead: Settings → Video → Renderer → Holy GL4ES, LTW, or MobileGlues.",
                        "Enable the Auto Graphics Optimizer (Settings → Video) so the launcher avoids Krypton-based renderers on GPUs where it's known to crash.",
                        "If you specifically need the Krypton Wrapper plugin, wait for an updated libng_gl4es.so build from its author."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.RESET_RENDERER_OVERRIDE, "Reset renderer override"))
                )
            ),
            // 2c. armeabi-v7a (32-bit ARM) UnsatisfiedLinkError on org.lwjgl.system.Callback -
            // seen in an uploaded log from a real 32-bit device (Realme RMX1825, API 29).
            // Root cause confirmed by inspecting this project's own jniLibs: arm64-v8a ships
            // BOTH liblwjgl.so (rebuilt for MC 26.3+/LWJGL 3.4.2) and liblwjgl-legacy.so (the
            // pre-rebuild native, for older MC versions - see LaunchArgs.kt's
            // getLwjglNativeLibraryOverride doc comment), but armeabi-v7a only ever shipped
            // ONE liblwjgl.so - it was never rebuilt for the same LWJGL 3.4.2 API surface the
            // Java-side lwjgl3 classes now expect, so a symbol the Java side expects
            // (Callback.getCallbackHandler) doesn't exist in the stale 32-bit native. Same
            // class of problem as the other native-only blockers in this file: prebuilt
            // binary, no native source/toolchain in this project to rebuild it from.
            Rule(
                title = "arm32_lwjgl_callback_unsatisfiedlink",
                matches = { has(it, "Callback.getCallbackHandler") && has(it, "UnsatisfiedLinkError") },
                diagnosis = fixed(
                    "32-bit ARM (armeabi-v7a) liblwjgl.so is out of date for this Minecraft version",
                    "This device is running the 32-bit ARM build. Unlike arm64-v8a (which ships both the " +
                        "current and a legacy liblwjgl.so and picks whichever the launching Minecraft version " +
                        "needs), armeabi-v7a only has one liblwjgl.so, and it wasn't rebuilt alongside arm64's - " +
                        "so it's missing a symbol the Java-side LWJGL classes now expect.",
                    listOf(
                        "This needs a rebuilt armeabi-v7a liblwjgl.so (matching the same LWJGL version arm64-v8a's current build uses) - not something fixable from within the app.",
                        "If you have another device, a 64-bit (arm64-v8a) one will not hit this.",
                        "Until a 32-bit rebuild is available, older Minecraft versions that only need the pre-3.4.x LWJGL API may still work better than newer ones on this device."
                    )
                )
            ),
            Rule(
                title = "osmesa_flush_frontbuffer",
                matches = { has(it, "OSMesaFlushFrontbuffer") },
                diagnosis = fixed(
                    "Zink/OSMesa renderer is missing a required symbol (OSMesaFlushFrontbuffer)",
                    "The bundled libOSMesa_8.so does not export OSMesaFlushFrontbuffer, which the Zink (OpenGL-over-Vulkan) " +
                        "renderer path needs on Minecraft 26.x to present finished frames.",
                    listOf(
                        "Switch renderer: Settings → Video → Renderer, pick a non-Zink option (OpenGL ES / Vulkan) instead of VulkanZink.",
                        "If you specifically need Zink, wait for a TurtleLauncher build with an updated libOSMesa_8.so that exports this symbol.",
                        "Try the Auto Graphics Optimizer (Settings → Video) so the launcher picks a renderer your GPU/driver actually supports."
                    ),
                    Severity.WARNING,
                    repairActions = listOf(RepairAction(RepairActionType.RESET_RENDERER_OVERRIDE, "Reset renderer override"))
                )
            ),
            // 3. AccessDeniedException creating dirs under the read-only APK lib folder.
            Rule(
                title = "access_denied_natives_dir",
                matches = { has(it, "AccessDeniedException") && has(it, "natives", "lib/arm64", "Files.createDirectories") },
                diagnosis = fixed(
                    "Permission denied while preparing native libraries (AccessDeniedException)",
                    "Minecraft 26.x tries to create subfolders inside its native-library extraction directory at startup. " +
                        "If that directory points at the app's read-only APK lib folder instead of a writable cache " +
                        "folder, every mkdir call throws AccessDeniedException and the game can't start.",
                    listOf(
                        "Update to the latest TurtleLauncher build, which always points the natives directory at a writable per-version cache folder.",
                        "Clear the app's cache below, or via Android Settings → Apps → TurtleLauncher → Storage → Clear Cache, and relaunch.",
                        "Make sure you're not running from a read-only/sandboxed storage location (e.g. some custom ROM \"app cloning\" features)."
                    ),
                    repairActions = listOf(
                        RepairAction(RepairActionType.CLEAR_NATIVES_CACHE, "Clear natives cache"),
                        RepairAction(RepairActionType.CLEAR_APP_CACHE, "Clear app cache"),
                        RepairAction(RepairActionType.FIX_PERMISSIONS, "Fix file permissions")
                    )
                )
            ),
            // 4. Out of memory.
            Rule(
                title = "oom",
                matches = { has(it, "OutOfMemoryError", "Java heap space", "GC overhead limit exceeded") },
                diagnosis = fixed(
                    "Out of memory (Java heap space)",
                    "The JVM ran out of allocated heap memory. This is usually caused by too many/heavy mods, a high " +
                        "render distance, or a RAM allocation that's too small for the modpack you're running.",
                    listOf(
                        "Increase the allocated RAM below, or in Settings → Game → RAM Allocation (but leave enough free for Android itself).",
                        "Lower render distance / particle / entity settings in-game once it loads.",
                        "Remove resource-heavy mods/shaders/resource packs you don't need.",
                        "Close other background apps before launching to free up real device RAM."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.INCREASE_RAM_ALLOCATION, "Increase RAM +1024MB"))
                )
            ),
            // 5. Could not reserve heap at JVM startup (Xmx too high for the device).
            Rule(
                title = "heap_reserve_failed",
                matches = { has(it, "Could not reserve enough space for", "Could not allocate metaspace", "Failed to map") },
                diagnosis = fixed(
                    "The JVM couldn't reserve enough memory to even start",
                    "The configured RAM allocation (-Xmx) is higher than what this device can actually provide as one " +
                        "contiguous block, so the JVM fails before Minecraft even begins loading.",
                    listOf(
                        "Lower RAM Allocation below, or in Settings → Game — try reducing it by 512–1024MB and relaunching.",
                        "On 32-bit devices, addressable memory is much lower than total RAM; keep allocation well under that limit.",
                        "Restart the device to defragment memory if this only started happening after a long uptime."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.LOWER_RAM_ALLOCATION, "Lower RAM -1024MB"))
                )
            ),
            // 6. Wrong Java version for the mod/Minecraft version.
            Rule(
                title = "wrong_java_version",
                matches = { has(it, "UnsupportedClassVersionError", "has been compiled by a more recent version of the Java Runtime") },
                diagnosis = fixed(
                    "Installed Java runtime is too old for this Minecraft/mod version",
                    "A class file was compiled for a newer Java version than the runtime currently selected for this " +
                        "Minecraft version supports.",
                    listOf(
                        "Let TurtleLauncher auto-pick the Java runtime, or manually select a newer one in the version's Java settings (Java 17/21/25 depending on the Minecraft version).",
                        "If you just updated Minecraft/a modpack, delete the version's cached Java selection so it re-resolves the requirement."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.REPAIR_RUNTIME, "Repair Java runtime"))
                )
            ),
            // 7. Mixin application failure (a mod's bytecode patch failed to apply).
            Rule(
                title = "mixin_failure",
                matches = { has(it, "MixinApplicatorStandard", "MixinTransformationException", "mixin apply failed", "MixinApplyError") },
                diagnosis = fixed(
                    "A mod's Mixin patch failed to apply",
                    "One of your mods uses Mixin to modify Minecraft's code, and that patch could not be applied — " +
                        "almost always because the mod is for a different Minecraft version or conflicts with another mod.",
                    listOf(
                        "Check the full log for the mixin's package name (usually matches a mod's id) and update or remove that mod.",
                        "Make sure every mod targets the exact Minecraft version and mod loader you're running.",
                        "Try removing recently-added mods one at a time to find the conflicting one."
                    )
                )
            ),
            // 8. Duplicate mod jars.
            Rule(
                title = "duplicate_mods",
                matches = { has(it, "DuplicateModsFoundException", "Duplicate mod", "found multiple mods") },
                diagnosis = fixed(
                    "Duplicate mod files detected",
                    "Two or more jar files in your mods folder declare the same mod ID, which the mod loader refuses " +
                        "to load (it can't know which one you actually want).",
                    listOf(
                        "Open the mods folder and remove the older/duplicate copy — keep only one jar per mod.",
                        "Check for a mod that got downloaded twice (once manually, once via the modpack installer)."
                    )
                )
            ),
            // 9. Missing/incompatible mandatory mod dependency.
            Rule(
                title = "missing_dependency",
                matches = {
                    has(it, "missing or unsupported mandatory dependencies", "ModResolutionException",
                        "Could not find required mod", "requires {", "is missing dependencies") &&
                        // ModResolutionException also wraps totally unrelated causes (a corrupted mod
                        // jar throws it too, via ModDiscoverer's ForkJoinTask wrapping). Rule 12
                        // (corrupted_zip) already explains that case correctly and more specifically,
                        // so don't also show this misleading "missing dependency" diagnosis alongside it.
                        !has(it, "zip END header not found", "invalid LOC header", "ZipException", "Truncated ZIP file")
                },
                diagnosis = fixed(
                    "A mod is missing one of its required dependencies",
                    "A mod declares another mod as a mandatory dependency, but that dependency isn't present (or its " +
                        "version doesn't satisfy what's required).",
                    listOf(
                        "TurtleLauncher's automatic dependency installer will try to fetch common missing dependencies from Modrinth the next time you launch this version.",
                        "If it can't be auto-resolved, check the error for the missing mod's name/ID and install it manually into the mods folder.",
                        "Make sure the dependency's version matches what the dependent mod expects (don't mix Minecraft versions)."
                    )
                )
            ),
            // 10. Vulkan device feature requirements not met.
            Rule(
                title = "vulkan_feature",
                matches = { has(it, "vkCreateDevice", "VK_ERROR", "required device feature", "VulkanBackend") && has(it, "vulkan", "Vulkan") },
                diagnosis = fixed(
                    "Vulkan renderer failed: a required GPU feature isn't supported",
                    "Minecraft's Vulkan backend requires certain GPU/driver features that this device's Vulkan driver " +
                        "doesn't expose, so device creation fails before rendering can start.",
                    listOf(
                        "Switch to OpenGL ES (Settings → Video → Renderer) if Vulkan keeps failing on this device — most renderers don't need Vulkan at all.",
                        "Update your device's GPU driver/Turnip driver plugin if one is installed.",
                        "Some Adreno/Mali chips don't expose every Vulkan 1.2 feature Minecraft's experimental Vulkan backend requires — this may not be fixable on this device."
                    )
                )
            ),
            // 11. Native (C/C++) crash — driver-level segfault, common on PowerVR GPUs.
            Rule(
                title = "native_crash",
                matches = { has(it, "SIGSEGV", "Native crash", "tombstone", "backtrace:") },
                diagnosis = fixed(
                    "The GPU driver crashed natively (SIGSEGV)",
                    "A native (non-Java) crash happened inside the graphics driver itself, not in Minecraft's Java " +
                        "code. This is most common on devices with PowerVR GPUs, where certain OpenGL code paths are " +
                        "known to crash the driver.",
                    listOf(
                        "Switch renderer: try Vulkan-Zink instead of OpenGL ES (or vice-versa) in Settings → Video.",
                        "Enable the Auto Graphics Optimizer so the launcher picks a renderer/driver combo known to work on your GPU.",
                        "Lower resolution scale, which reduces load on the driver and can avoid some driver crash conditions.",
                        "Update TurtleLauncher — PowerVR-specific renderer fixes are an active area of work."
                    ),
                    Severity.WARNING
                )
            ),
            // 12. Corrupted / truncated downloaded file.
            Rule(
                title = "corrupted_zip",
                matches = { has(it, "zip END header not found", "invalid LOC header", "ZipException", "Truncated ZIP file") },
                diagnosis = { text ->
                    // Fabric's ModDiscoverer reports this as: "Error analyzing [<path>]: java.util.zip...".
                    // When present, name the exact file so the fix is one tap instead of a guessing game.
                    val badFilePath = Regex("Error analyzing \\[([^\\]]+)]:.*?(?:ZipException|LOC header|END header)")
                        .find(text)?.groupValues?.getOrNull(1)
                    val badFileName = badFilePath?.substringAfterLast('/')

                    Diagnosis(
                        title = badFileName?.let { "A downloaded file is corrupted or incomplete ($it)" }
                            ?: "A downloaded file is corrupted or incomplete",
                        cause = (badFilePath?.let { "The file below is truncated or corrupted, usually from an interrupted download:\n$it\n\n" } ?: "") +
                            "A jar/zip file (a library, a mod, or the Minecraft client itself) is truncated or corrupted, " +
                            "usually from an interrupted download.",
                        fixSteps = listOfNotNull(
                            badFileName?.let { "Delete \"$it\" below and download a fresh copy — that exact file is the corrupted one." }
                                ?: "If it's a manually-installed mod, delete it and download it again.",
                            "Go to Version Manager → this version → Verify/repair files, or simply re-download the version.",
                            "Check your storage isn't full — downloads can silently truncate when disk space runs out."
                        ),
                        repairActions = listOfNotNull(
                            badFilePath?.let { RepairAction(RepairActionType.DELETE_FILE, "Delete corrupted file", it) }
                        )
                    )
                }
            ),
            // 13. Authentication / session problems.
            Rule(
                title = "auth_failure",
                matches = { has(it, "InvalidCredentialsException", "Invalid session", "401 Unauthorized", "ForbiddenOperationException") },
                diagnosis = fixed(
                    "Account session is invalid or expired",
                    "The selected account's login session was rejected by the authentication server.",
                    listOf(
                        "Open Accounts and log back in to refresh the session.",
                        "If using a third-party login (authlib-injector/ely.by/LittleSkin), double-check the server URL is still correct.",
                        "For offline play, switch to a local/offline account instead."
                    )
                )
            ),
            // 14. Generic NoClassDefFoundError / ClassNotFoundException NOT already covered
            //     by the native-library rule above (that one also triggers these as a
            //     side-effect, so we explicitly exclude it here to avoid a confusing
            //     double diagnosis).
            Rule(
                title = "generic_missing_class",
                matches = {
                    has(it, "NoClassDefFoundError", "ClassNotFoundException") &&
                        !has(it, "Failed to locate library:", "no pojavexec in java.library.path", "UnsatisfiedLinkError") &&
                        !has(it, "net.fabricmc.loader.impl.EntrypointException", "cpw.mods.modlauncher", "net.neoforged.fml.loading")
                },
                diagnosis = { text ->
                    val missingClass = Regex("(?:NoClassDefFoundError|ClassNotFoundException):?\\s*([\\w./$]+)")
                        .find(text)?.groupValues?.getOrNull(1)
                    Diagnosis(
                        "A required class could not be found" + (missingClass?.let { " ($it)" } ?: ""),
                        "Something on the classpath is missing or incompatible — most often a mod built for a " +
                            "different mod loader/Minecraft version, or a mod whose own dependency jar is missing.",
                        listOf(
                            "If this started after adding a mod, remove the most recently added mod and try again.",
                            "Double-check every mod matches both the Minecraft version and the mod loader (Fabric/Forge/NeoForge/Quilt) you're using.",
                            "TurtleLauncher's automatic dependency installer will try to fetch common missing dependencies on the next launch."
                        )
                    )
                }
            ),
            // 15. Fast Boot was enabled for this launch — surface it as a likely contributor
            // whenever the crash also looks like a checksum/corruption-class failure, since
            // Fast Boot skips the checks that would normally have caught a bad file.
            Rule(
                title = "fast_boot_skipped_verification",
                matches = {
                    com.endiq.turtlelauncher.setting.AllSettings.fastBoot.getValue() &&
                        has(it, "zip END header not found", "invalid LOC header", "ZipException", "Truncated ZIP file",
                            "NoClassDefFoundError", "ClassNotFoundException", "Failed to locate library:")
                },
                diagnosis = fixed(
                    "Fast Boot may have let a corrupted/incomplete file through",
                    "Fast Boot was enabled for this launch, which skips the checksum verification that normally " +
                        "catches truncated or corrupted downloads before the game starts. The error above is consistent " +
                        "with a bad file slipping through.",
                    listOf(
                        "Turn Fast Boot off below, then try launching again — this restores the checksum pass that Fast Boot skips.",
                        "If it still fails, open Version Manager → this version → Verify/repair files to force a fresh checksum pass.",
                        "You can re-enable Fast Boot afterward in Experimental Settings once you've confirmed the files are good."
                    ),
                    Severity.WARNING,
                    repairActions = listOf(RepairAction(RepairActionType.DISABLE_FAST_BOOT, "Turn off Fast Boot"))
                )
            ),
            // 16. Fabric Loader failed during its own entrypoint/init sequence — a mod's init
            // code threw during Fabric's startup, distinct from the generic Mixin/dependency
            // rules above (those catch Fabric's ModResolutionException already).
            Rule(
                title = "fabric_entrypoint_failure",
                matches = {
                    has(it, "net.fabricmc.loader.impl.EntrypointException", "Could not execute entrypoint stage",
                        "net.fabricmc.loader.impl.FormattedException")
                },
                diagnosis = { text ->
                    val modId = Regex("""Could not execute entrypoint stage '[^']*' due to errors, provided by '([^']+)'""")
                        .find(text)?.groupValues?.getOrNull(1)
                        ?: Regex("""EntrypointException:\s*Exception executing entrypoint.*?'([^']+)'""").find(text)?.groupValues?.getOrNull(1)
                    Diagnosis(
                        title = "A Fabric mod failed during startup" + (modId?.let { " ($it)" } ?: ""),
                        cause = (modId?.let { "The mod \"$it\" " } ?: "A mod ") +
                            "threw an exception while Fabric Loader was running its startup entrypoints — this happens " +
                            "inside the mod's own init code, before the game itself has even started loading.",
                        fixSteps = listOf(
                            "Check the full log's \"Caused by:\" line right after the entrypoint error for the real exception from the mod.",
                            "Update the mod named above (or Fabric API) to the version matching your Minecraft version — this is the single most common cause.",
                            "If it still fails after updating, remove that mod to confirm it's the cause, then report the log to the mod's author."
                        ),
                        severity = Severity.CRITICAL
                    )
                }
            ),
            // 17. Forge / ModLauncher failed during mod discovery, transformation, or loading.
            // Excludes NeoForge's own namespace so the more specific rule below doesn't get
            // shadowed (Forge and NeoForge share cpw.mods.modlauncher but not net.minecraftforge).
            Rule(
                title = "forge_modlauncher_failure",
                matches = {
                    has(it, "cpw.mods.modlauncher", "net.minecraftforge.fml.loading", "TRANSFORMER SERVICE ERROR",
                        "net.minecraftforge.fml.common.ModLoadingException") && !has(it, "net.neoforged")
                },
                diagnosis = fixed(
                    "Forge failed while discovering or loading mods",
                    "Forge's ModLauncher/FML layer hit an error while scanning, transforming, or loading mods — before " +
                        "Minecraft itself starts. Almost always one specific mod jar is the cause.",
                    listOf(
                        "Check the log for the mod's jar filename near the error (often listed under \"Mod File:\" or in a \"Caused by:\" line) and update or remove it.",
                        "Make sure every mod's build targets the exact Forge and Minecraft version you're running — Forge mods are not cross-version compatible.",
                        "If this started after adding a mod, remove the most recently added one first."
                    ),
                    Severity.CRITICAL
                )
            ),
            // 18. NeoForge (Forge's actively-maintained fork) failed during loading.
            Rule(
                title = "neoforge_failure",
                matches = {
                    has(it, "net.neoforged.fml.loading", "net.neoforged.neoforgespi", "net.neoforged.fml.ModLoadingException")
                },
                diagnosis = fixed(
                    "NeoForge failed while discovering or loading mods",
                    "NeoForge's loading layer hit an error while scanning, transforming, or loading mods — before " +
                        "Minecraft itself starts. Almost always one specific mod jar is the cause.",
                    listOf(
                        "Check the log for the mod's jar filename near the error and update or remove it.",
                        "Make sure every mod's build targets NeoForge specifically (not Forge) and the exact Minecraft version you're running — the two loaders aren't interchangeable.",
                        "If this started after adding a mod, remove the most recently added one first."
                    ),
                    Severity.CRITICAL
                )
            ),
            // 18b. OpenAL sound library failed to load or initialize.
            // The bundled libopenal.so (openal-soft-release.aar) only ships the OpenSL ES
            // backend (\"opensl\") — no \"android\" (AudioTrack/JNI), no \"oboe\", no desktop
            // ALSA/Pulse/JACK. Setting ALSOFT_DRIVERS to a non-existent backend (e.g. \"android\")
            // makes OpenAL-Soft log \"No playback backend available!\" and Minecraft ends up
            // completely silent — no crash, just no audio. This rule catches both the crash case
            // (UnsatisfiedLinkError when the library can't be found) and the silent-failure case.
            Rule(
                title = "openal_sound_failure",
                matches = {
                    has(it, "Failed to pre-load libopenal.so", "Failed to load openal",
                        "UnsatisfiedLinkError.*openal", "no openal in java.library.path",
                        "No playback backend available", "No capture backend available",
                        "Failed to initialize backend") ||
                        (has(it, "openal") && has(it, "UnsatisfiedLinkError")) ||
                        (has(it, "ALSOFT") && has(it, "backend"))
                },
                diagnosis = fixed(
                    "OpenAL sound library failed to load (libopenal.so)",
                    "Minecraft's sound system (OpenAL) couldn't load libopenal.so, or it loaded but " +
                        "couldn't find a usable audio backend. The bundled libopenal.so only contains the " +
                        "OpenSL ES backend (\"opensl\"). Requesting a non-existent backend (e.g. \"android\") " +
                        "results in \"No playback backend available!\" and complete silence on every version.",
                    listOf(
                        "Update to the latest TurtleLauncher build — the ALSOFT_DRIVERS=opensl fix is now applied automatically on every launch (the previous \"android\" value requested a backend this build doesn't contain).",
                        "If sound is still silent (no crash, just no audio): check that your device's media volume isn't at zero, that no other app is holding exclusive audio focus, and that the in-game master volume (options.txt soundCategory_master) isn't 0.0.",
                        "If the launcher log shows 'Failed to pre-load libopenal.so': reinstall the APK to ensure native libraries are present.",
                        "If log shows 'Failed to initialize backend \"opensl\"' or 'Failed to initialize OpenSL device': your device's OpenSL ES driver may be broken — try a different device or a newer Android version."
                    ),
                    Severity.WARNING
                )
            ),
            // 19. Missing/corrupted asset objects (textures, sounds, lang files).
            Rule(
                title = "missing_assets",
                matches = {
                    (has(it, "assets/objects", "assets/indexes") && has(it, "FileNotFoundException", "NoSuchFileException")) ||
                        has(it, "Bad hash for asset")
                },
                diagnosis = fixed(
                    "Game assets are missing or corrupted (textures/sounds/lang files)",
                    "Minecraft's asset downloader couldn't find or verify one or more files under the assets folder — " +
                        "usually from an interrupted or partial asset download, or an asset index that doesn't match " +
                        "what's actually on disk.",
                    listOf(
                        "Open Version Manager → this version → Verify/repair files to re-check and re-download assets.",
                        "If it keeps happening, delete the assets folder for this version entirely and let it redownload from scratch.",
                        "Check your storage isn't full — asset downloads can silently truncate when disk space runs out."
                    ),
                    Severity.WARNING,
                    repairActions = listOf(RepairAction(RepairActionType.VERIFY_GAME_FILES, "Verify & re-download game files"))
                )
            ),
            // 20. Corrupted or unparsable mod/loader config file (Forge TOML config or a
            // mod's own JSON config), distinct from rule 12 (corrupted_zip is for jar/zip
            // archives; this is for text config files that fail to parse).
            Rule(
                title = "corrupted_config",
                matches = {
                    has(it, "com.electronwill.nightconfig.core.io.ParsingException", "MalformedJsonException") ||
                        (has(it, "config") && has(it, "JsonSyntaxException", "Unexpected character", "not a valid config"))
                },
                diagnosis = { text ->
                    val configPath = Regex("""([\w\-./\\]*config[\w\-./\\]*\.(?:toml|json5?|cfg|properties))""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)
                    Diagnosis(
                        title = "A config file is corrupted or fails to parse" + (configPath?.let { " (${it.substringAfterLast('/')})" } ?: ""),
                        cause = (configPath?.let { "The file below couldn't be parsed:\n$it\n\n" } ?: "") +
                            "A mod or loader config file (TOML/JSON) has invalid syntax, often from a manual edit, a bad " +
                            "merge, or the file being cut off mid-write.",
                        fixSteps = listOfNotNull(
                            configPath?.let { "Delete \"${it.substringAfterLast('/')}\" below — most mods regenerate a fresh default config automatically on next launch." }
                                ?: "Delete the config file named in the log — most mods regenerate a fresh default config automatically.",
                            "If you'd made manual tweaks to that config, reapply them to the freshly-regenerated file afterward.",
                            "If it keeps recurring right after each regeneration, the mod itself may be writing invalid data — report it to the mod's author."
                        ),
                        severity = Severity.WARNING,
                        repairActions = listOfNotNull(
                            configPath?.let { RepairAction(RepairActionType.DELETE_FILE, "Delete corrupted config", it) },
                            RepairAction(RepairActionType.RESTORE_CONFIGS, "Restore default configs")
                        )
                    )
                }
            ),
            // 21. Holy GL4ES (libgl4es_114.so) SIGSEGV inside glDeleteFramebuffersEXT, seen on
            // a real device log right at Minecraft startup (RenderTarget's very first
            // create-then-immediately-resize sequence, which deletes the just-created FBO
            // before making the real one). Prebuilt binary, no native source in this project
            // to patch or debug further - but there IS a real, already-wired-up, user-facing
            // lever worth trying first: Settings → Video → Performance already has a
            // "native object pooling" switch (AllSettings.nativeObjectPooling) that's the
            // literal thing controlling LIBGL_RECYCLEFBO (see JREUtils.launchJavaVM) - FBO
            // *recycling* being on is what makes the just-created FBO's handle eligible for
            // reuse/deletion in the first place, so it's the most directly implicated existing
            // setting for a crash inside FBO deletion specifically, even though this hasn't
            // been confirmed against a real device with it turned off yet.
            Rule(
                title = "gl4es_delete_framebuffers_sigsegv",
                matches = { has(it, "glDeleteFramebuffersEXT") && has(it, "libgl4es", "SIGSEGV") },
                diagnosis = fixed(
                    "Holy GL4ES crashed deleting a framebuffer object (glDeleteFramebuffersEXT)",
                    "A native SIGSEGV inside libgl4es_114.so itself, not a Java exception - happens right at " +
                        "Minecraft startup, in the framebuffer create-then-resize sequence RenderTarget always " +
                        "runs once during init. It's a prebuilt upstream binary with no native source in this " +
                        "project to patch. The most directly implicated lever already in this launcher is FBO " +
                        "recycling (LIBGL_RECYCLEFBO) - candidate fix below, not yet confirmed against a real " +
                        "device with it off.",
                    listOf(
                        "Try Settings → Video → Performance → turn off \"Native object pooling\" (this is the switch " +
                            "that sets LIBGL_RECYCLEFBO), then relaunch - it directly controls FBO reuse, the exact " +
                            "operation that's crashing.",
                        "If that doesn't help, try a different renderer for this version instead (LTW or MobileGlues).",
                        "Report back either way - this rule's fix step is a reasoned guess based on what the setting " +
                            "actually does, not a confirmed fix yet."
                    ),
                    repairActions = listOf(RepairAction(RepairActionType.DISABLE_FBO_RECYCLING, "Turn off native object pooling"))
                )
            ),
            // 22. MC 26.3+'s new SDL3-based LWJGL backend (org.lwjgl.sdl.SDLInit.SDL_Init)
            // SIGSEGV inside libSDL3.so's Android backend init path, confirmed via a real
            // device hs_err_pid*.log (si_addr=0x0, SEGV_MAPERR - a null pointer dereference,
            // not memory corruption) at the same offset across two separate crash logs, so
            // consistently reproducible rather than flaky. Call chain from the log itself:
            // SDL_Init -> SDL_InitSubSystem -> three internal (unexported, stripped) helpers
            // -> crash, inside SDL's Android JNI backend code.
            //
            // Root cause, now established rather than guessed: SDL's Android backend reads
            // statics its own SDLActivity Java glue normally fills in (Activity, SDLSurface,
            // layout, clipboard handler). This launcher never starts SDLActivity as an
            // Activity, so those were all null and SDL_Init dereferenced one.
            //
            // Fix status: the mechanism Amethyst-Android uses for this is now ported -
            // SDLActivity.externalInitialize() / SDLSurface.setNativeSurface(), plus this
            // launcher's own surface lifecycle being forwarded into SDL (see
            // SdlAndroidJniPrep's class doc for which half of their fix is in, which isn't,
            // and why the native half can't be). That code has NOT been verified on real
            // hardware, so this rule stays: if the crash still happens the player sees the
            // diagnosis below, and the "TurtleSDL3: getNativeSurface" log line now says
            // whether SDL was handed a real Surface - the piece that was invisible before.
            Rule(
                title = "sdl3_android_init_sigsegv",
                matches = { has(it, "libSDL3.so") && has(it, "SDL_InitSubSystem", "SDL_Init") && has(it, "SIGSEGV") },
                diagnosis = { _ ->
                    Diagnosis(
                        title = "MC 26.3+'s SDL3 backend crashed on Android during SDL_Init (libSDL3.so)",
                        cause = "A null-pointer SIGSEGV inside libSDL3.so's own Android backend init code, triggered " +
                            "by LWJGL's org.lwjgl.sdl.SDL_Init(). SDL's Android backend expects an Activity/SDLSurface " +
                            "pair from its own Java glue; this launcher now sets that up before the game JVM starts " +
                            "(SdlAndroidJniPrep), but that path is new and not yet confirmed on a real device - so a " +
                            "crash here means either it didn't take effect for this launch, or the renderer in use " +
                            "can't create its EGL window under SDL at all.",
                        fixSteps = listOf(
                            "Switch renderer for this version: Settings → Video → Renderer. Amethyst-Android's own " +
                                "release notes list MobileGlues and Krypton Wrapper as crashing on MC 26.3-snapshot4+ " +
                                "\"due to changes in how SDL creates EGL window\" - Zink or LTW are the safer picks there.",
                            "Leave Settings → Experimental → \"LWJGL compatibility mode\" on Auto so the launcher picks " +
                                "the LWJGL native matching this version's own manifest.",
                            "If it still fails, play an MC version at or below 26.2 (pre-SDL3/GLFW-based LWJGL) and " +
                                "report the log - the new SDL log lines say whether SDL got a real Surface, which is " +
                                "what's needed to finish this fix.",
                            "A native backtrace with symbols for the three unexported frames below SDL_InitSubSystem " +
                                "(gdb/lldb attached to a launch) would give more than the stripped binary can."
                        ),
                        severity = Severity.CRITICAL
                    )
                }
            )
        )
    }

    // ── Lightweight local telemetry (counts only, never leaves the device) ────
    // Tracks how many times each rule has actually fired, purely so you can see
    // which CrashAnalyzer rules matter in practice. No data is ever uploaded.
    private const val TELEMETRY_PREFS = "crash_analyzer_telemetry"

    private fun recordRuleFired(ruleTitle: String) {
        runCatching {
            val ctx = com.endiq.turtlelauncher.context.ContextExecutor.getApplication()
            val prefs = ctx.getSharedPreferences(TELEMETRY_PREFS, android.content.Context.MODE_PRIVATE)
            val current = prefs.getInt(ruleTitle, 0)
            prefs.edit().putInt(ruleTitle, current + 1).apply()
        }
    }

    /** Returns every rule's local fire-count, sorted most-frequent first. Counts only, no upload. */
    @JvmStatic
    fun getTelemetrySnapshot(context: android.content.Context): List<Pair<String, Int>> {
        return runCatching {
            val prefs = context.getSharedPreferences(TELEMETRY_PREFS, android.content.Context.MODE_PRIVATE)
            prefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.sortedByDescending { it.second }
        }.getOrDefault(emptyList())
    }

    // ── Custom rules (user-editable, no rebuild required) ─────────────────────
    // Stored as a JSON array in AllSettings.customCrashRules:
    // [{"pattern":"some substring or /regex/","tip":"what to do about it","title":"optional"}]
    // Matching is substring (case-insensitive) unless the pattern is wrapped in "/.../"
    // in which case it's treated as a regular expression.
    data class CustomRule(val pattern: String, val tip: String, val title: String? = null)

    @JvmStatic
    fun getCustomRules(): List<CustomRule> {
        return runCatching {
            val json = com.google.gson.JsonParser.parseString(
                com.endiq.turtlelauncher.setting.AllSettings.customCrashRules.getValue()
            ).asJsonArray
            json.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val pattern = obj.get("pattern")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val tip = obj.get("tip")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                CustomRule(pattern, tip, title)
            }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun addCustomRule(pattern: String, tip: String, title: String? = null) {
        val current = getCustomRules().toMutableList()
        current.add(CustomRule(pattern, tip, title))
        saveCustomRules(current)
    }

    @JvmStatic
    fun removeCustomRule(index: Int) {
        val current = getCustomRules().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            saveCustomRules(current)
        }
    }

    private fun saveCustomRules(rules: List<CustomRule>) {
        val array = com.google.gson.JsonArray()
        rules.forEach { rule ->
            val obj = com.google.gson.JsonObject()
            obj.addProperty("pattern", rule.pattern)
            obj.addProperty("tip", rule.tip)
            rule.title?.let { obj.addProperty("title", it) }
            array.add(obj)
        }
        com.endiq.turtlelauncher.setting.AllSettings.customCrashRules.put(array.toString()).save()
    }

    private fun matchCustomRules(text: String): List<Diagnosis> {
        return getCustomRules().mapNotNull { rule ->
            val matched = runCatching {
                if (rule.pattern.startsWith("/") && rule.pattern.endsWith("/") && rule.pattern.length > 1) {
                    Regex(rule.pattern.substring(1, rule.pattern.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(text)
                } else {
                    text.contains(rule.pattern, ignoreCase = true)
                }
            }.getOrDefault(false)
            if (!matched) return@mapNotNull null
            Diagnosis(
                title = rule.title ?: "Custom rule matched: ${rule.pattern.take(40)}",
                cause = "This matched a custom crash-analyzer rule you added.",
                fixSteps = listOf(rule.tip),
                severity = Severity.INFO
            )
        }
    }

    // ── Crash history (last N crashes, not just the most recent) ──────────────
    private const val MAX_HISTORY = 20

    data class CrashHistoryEntry(val timestampMs: Long, val exitCode: Int, val summary: String)

    @JvmStatic
    fun getCrashHistory(): List<CrashHistoryEntry> {
        return runCatching {
            val json = com.google.gson.JsonParser.parseString(
                com.endiq.turtlelauncher.setting.AllSettings.crashHistoryList.getValue()
            ).asJsonArray
            json.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val ts = obj.get("ts")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
                val code = obj.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
                val summary = obj.get("summary")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                CrashHistoryEntry(ts, code, summary)
            }
        }.getOrDefault(emptyList())
    }

    private fun pushCrashHistory(exitCode: Int, summary: String) {
        val current = getCrashHistory().toMutableList()
        current.add(0, CrashHistoryEntry(System.currentTimeMillis(), exitCode, summary.take(500)))
        while (current.size > MAX_HISTORY) current.removeAt(current.lastIndex)

        val array = com.google.gson.JsonArray()
        current.forEach { entry ->
            val obj = com.google.gson.JsonObject()
            obj.addProperty("ts", entry.timestampMs)
            obj.addProperty("code", entry.exitCode)
            obj.addProperty("summary", entry.summary)
            array.add(obj)
        }
        com.endiq.turtlelauncher.setting.AllSettings.crashHistoryList.put(array.toString()).save()
    }

    @JvmStatic
    fun clearCrashHistory() {
        com.endiq.turtlelauncher.setting.AllSettings.crashHistoryList.put("[]").save()
    }

    /** Runs every rule against [text], returning every diagnosis that matched (deduplicated by title). */
    private fun matchRules(text: String): List<Diagnosis> {
        if (text.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val results = mutableListOf<Diagnosis>()
        for (rule in rules) {
            if (rule.matches(text)) {
                val diagnosis = rule.diagnosis(text)
                if (seen.add(diagnosis.title)) {
                    results.add(diagnosis)
                    recordRuleFired(rule.title)
                }
            }
        }
        matchCustomRules(text).forEach { d ->
            if (seen.add(d.title)) results.add(d)
        }
        return results
    }

    /** Best-effort extraction of the "real" exception for the generic fallback diagnosis. */
    private fun extractKeyException(text: String): String? {
        val causedByLines = Regex("^Caused by:.*$", RegexOption.MULTILINE).findAll(text).map { it.value.trim() }.toList()
        if (causedByLines.isNotEmpty()) return causedByLines.last()

        val exceptionLine = Regex("^Exception in thread \"[^\"]*\".*$", RegexOption.MULTILINE).find(text)?.value?.trim()
        if (exceptionLine != null) return exceptionLine

        val descriptionLine = Regex("^Description:\\s*(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.trim()
        return descriptionLine
    }

    private fun genericExitFallback(text: String): Diagnosis {
        val keyException = extractKeyException(text)
        return Diagnosis(
            title = "Unhandled error (no specific known cause matched)",
            cause = keyException?.let { "The clearest error found in the log was:\n$it" }
                ?: "No specific cause could be automatically identified from the available log output.",
            fixSteps = listOf(
                "Open the full log (in-game log viewer, or share the log file) for the complete stack trace.",
                "If this started after adding/updating a mod, try removing the most recent one.",
                "Make sure the selected Java runtime matches what this Minecraft version requires (Settings → Java).",
                "Try switching renderer (OpenGL ES / Vulkan-Zink) in Settings → Video.",
                "Verify this version's files in Version Manager in case a download is corrupted."
            ),
            severity = Severity.WARNING
        )
    }

    // ── Last-analysis holder ───────────────────────────────────────────────────
    // ErrorActivity's game-crash path currently only receives pre-formatted diagnosis
    // *text* through an Intent extra (see JREUtils → ErrorActivity.showExitMessage),
    // and Diagnosis/RepairAction aren't Parcelable. Rather than plumb that through every
    // call site, the structured result of the most recent analysis is kept here so the UI
    // can still offer one-click repair / export / search-online buttons for it. Read-only
    // from the UI's perspective; only [analyze] and [analyzeFrozenState] write to it.
    @Volatile private var lastDiagnoses: List<Diagnosis> = emptyList()
    @Volatile private var lastGameVersion: Version? = null
    @Volatile private var lastLogText: String = ""

    @JvmStatic
    fun getLastDiagnoses(): List<Diagnosis> = lastDiagnoses

    @JvmStatic
    fun getLastGameVersion(): Version? = lastGameVersion

    @JvmStatic
    fun getLastLogText(): String = lastLogText

    /** Analyzes raw log/crash-report text and returns every matched diagnosis (never empty if [logText] is non-blank). */
    @JvmStatic
    @JvmOverloads
    fun analyze(logText: String, crashReportText: String? = null, gameVersion: Version? = null): List<Diagnosis> {
        val combined = buildString {
            append(logText)
            if (!crashReportText.isNullOrBlank()) {
                append("\n")
                append(crashReportText)
            }
        }
        if (combined.isBlank()) return emptyList()

        val matched = matchRules(combined)
        val result = matched.ifEmpty { listOf(genericExitFallback(combined)) }
        lastDiagnoses = result
        lastGameVersion = gameVersion
        lastLogText = combined
        return result
    }

    /**
     * Specialised analysis for a game that is still running but appears to have
     * stopped producing any output (a hang / black screen that never crashes).
     * Prefers a real matched rule if the partial log already shows one; otherwise
     * falls back to a "frozen, no crash" specific message instead of the generic
     * post-exit fallback (which assumes the process already died).
     */
    @JvmStatic
    @JvmOverloads
    fun analyzeFrozenState(partialLogText: String, gameVersion: Version? = null): Diagnosis {
        val matched = matchRules(partialLogText)
        val diagnosis = matched.firstOrNull() ?: Diagnosis(
            title = "Game appears to be frozen (no crash reported)",
            cause = "Minecraft's process is still running, but no new log output has appeared for a while. This " +
                "usually happens during slow shader/world/datafixer loading on a weaker GPU driver, or when the " +
                "render thread is stuck waiting on the GPU without ever producing an error.",
            fixSteps = listOf(
                "Wait a little longer — the first launch after installing/updating can take a long time compiling shaders and data fixers.",
                "If it never recovers, force-close and try a different renderer (OpenGL ES / Vulkan-Zink) in Settings → Video.",
                "Lower allocated RAM slightly if the device is under memory pressure — a fully swapping device can stall like this.",
                "Check whether the device is thermal-throttling; a hot device may pause the GPU for long stretches."
            ),
            severity = Severity.WARNING
        )
        lastDiagnoses = listOf(diagnosis)
        lastGameVersion = gameVersion
        lastLogText = partialLogText
        return diagnosis
    }

    /** Formats one or more diagnoses as plain text suitable for a TextView/TipDialog message. */
    @JvmStatic
    fun formatForDisplay(diagnoses: List<Diagnosis>, exitCode: Int? = null): String {
        if (diagnoses.isEmpty()) return ""
        val multiple = diagnoses.size > 1
        val sb = StringBuilder()
        sb.append(if (multiple) "Crash analysis — ${diagnoses.size} possible issues found:" else "Crash analysis:")

        diagnoses.forEachIndexed { index, d ->
            sb.append("\n\n")
            sb.append(if (multiple) "${index + 1}. ${d.title}" else d.title)
            sb.append("\n").append(d.cause)
            if (d.fixSteps.isNotEmpty()) {
                sb.append("\n\nSuggested fix:")
                d.fixSteps.forEach { step -> sb.append("\n • ").append(step) }
            }
        }

        exitCode?.let { sb.append("\n\n(Process exit code: $it)") }
        return sb.toString()
    }

    /** Reads up to [maxBytes] from the end of [file]. Returns "" if the file doesn't exist or can't be read. */
    @JvmStatic
    fun tailOf(file: File, maxBytes: Int): String {
        if (!file.exists() || !file.isFile) return ""
        return runCatching {
            val length = file.length()
            val readLength = minOf(length, maxBytes.toLong()).toInt()
            if (readLength <= 0) return@runCatching ""
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - readLength)
                val buffer = ByteArray(readLength)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private const val MAX_LOG_BYTES = 64 * 1024
    private const val MAX_CRASH_REPORT_BYTES = 48 * 1024

    /**
     * Turns any [ModConflictDetector] findings for [gameVersion]'s mods folder into
     * crash-diagnosis entries. A detected mixin-target conflict is one of the most
     * concrete, actionable things CrashAnalyzer can tell someone — surfaced here so it
     * shows up automatically as part of crash diagnosis, not just as a separate
     * pre-launch warning dialog (see [com.endiq.turtlelauncher.feature.mod.ModAutoMaintenance]).
     */
    private fun modConflictDiagnoses(gameVersion: Version?): List<Diagnosis> {
        if (gameVersion == null) return emptyList()
        return runCatching {
            val modsFolder = File(gameVersion.getGameDir(), "mods")
            if (!modsFolder.isDirectory) return@runCatching emptyList()
            com.endiq.turtlelauncher.feature.mod.ModConflictDetector.detectConflicts(modsFolder)
                .map { conflict ->
                    Diagnosis(
                        title = "Mod conflict detected: ${conflict.modNames.joinToString(" + ")}",
                        cause = "${conflict.modNames.joinToString(", ")} all patch the same game class " +
                            "(${conflict.targetClass}) via Mixin. When two mods rewrite the same class's bytecode, " +
                            "one patch can silently break the other — this is one of the most common causes of an " +
                            "otherwise cryptic Mixin crash on startup.",
                        fixSteps = listOf(
                            "Update every mod listed above to its latest version first — this exact conflict is often already fixed upstream.",
                            "If it still conflicts after updating, remove one of the listed mods (whichever you need less) and relaunch.",
                            "Check each mod's page/Discord for a known incompatibility notice with the others listed here."
                        ),
                        severity = Severity.WARNING
                    )
                }
        }.getOrDefault(emptyList())
    }

    /**
     * Ported: if [diagnoses] is *only* the generic "no specific known cause matched" fallback,
     * ask [AiCrashAdvisor] for a suggestion and append it. Never called when a real rule (or a
     * mod-conflict finding) already explains the crash — the AI is strictly a last resort for
     * when TurtleLauncher's own knowledge base genuinely doesn't know what happened.
     */
    private fun withAiFallback(diagnoses: List<Diagnosis>, fullLogText: String): List<Diagnosis> {
        val isOnlyGenericFallback = diagnoses.size == 1 &&
            diagnoses[0].title.startsWith("Unhandled error")
        if (!isOnlyGenericFallback) return diagnoses

        val aiSuggestion = runCatching { AiCrashAdvisor.getSuggestion(fullLogText) }.getOrNull() ?: return diagnoses
        return diagnoses + Diagnosis(
            title = "AI-suggested fix (experimental)",
            cause = "None of TurtleLauncher's known crash patterns matched, so this suggestion came from the " +
                "AI crash helper you enabled in Settings — double-check it before relying on it.",
            fixSteps = listOf(aiSuggestion),
            severity = Severity.INFO
        )
    }

    // ── One-click repair ───────────────────────────────────────────────────────

    private const val MIN_RAM_MB = 512
    private const val MAX_RAM_MB = 8192
    private const val RAM_STEP_MB = 1024

    /**
     * Actually performs [action] (as opposed to the [Diagnosis.fixSteps] text, which only
     * describes what to do). [gameVersion] is required for version-scoped actions
     * (clearing natives cache, resetting a renderer override) and can be null for
     * global ones (Fast Boot, app cache, RAM). Safe to call from a background thread;
     * never throws.
     */
    @JvmStatic
    fun executeRepair(action: RepairAction, gameVersion: Version? = null): RepairResult {
        return runCatching {
            when (action.type) {
                RepairActionType.CLEAR_NATIVES_CACHE -> {
                    // Must match the exact same key LaunchArgs.resolveNativeLibraryPath() uses
                    // for java.library.path (versionName, i.e. the version folder name) — NOT
                    // Version.getVersionInfo().id, which is a different, unrelated field on
                    // TurtleLauncher's own VersionInfo model (minecraftVersion/loaderInfo only).
                    val versionId = gameVersion?.getVersionName()
                    if (versionId == null) {
                        RepairResult(false, "No active game version to clear the natives cache for.")
                    } else {
                        val dir = File(PathManager.DIR_CACHE, "natives/$versionId")
                        if (dir.exists()) FileUtils.deleteDirectory(dir)
                        RepairResult(true, "Cleared cached native libraries for $versionId. Relaunch the game.")
                    }
                }

                RepairActionType.RESET_RENDERER_OVERRIDE -> {
                    if (gameVersion == null) {
                        RepairResult(false, "No active game version to reset the renderer override for.")
                    } else {
                        gameVersion.getVersionConfig().apply { setRenderer("") }.save()
                        RepairResult(true, "Reset this version's renderer to the launcher default. Pick a different one in Settings → Video if needed.")
                    }
                }

                RepairActionType.CLEAR_APP_CACHE -> {
                    if (PathManager.DIR_APP_CACHE.isDirectory) FileUtils.cleanDirectory(PathManager.DIR_APP_CACHE)
                    RepairResult(true, "App cache cleared.")
                }

                RepairActionType.DISABLE_FAST_BOOT -> {
                    AllSettings.fastBoot.put(false).save()
                    RepairResult(true, "Fast Boot turned off. Try launching again.")
                }

                RepairActionType.DISABLE_FBO_RECYCLING -> {
                    AllSettings.nativeObjectPooling.put(false).save()
                    RepairResult(true, "Native object pooling (FBO recycling) turned off. Try launching again - this is an unconfirmed candidate fix, so please report back either way.")
                }

                RepairActionType.DELETE_FILE -> {
                    val path = action.targetPath
                    if (path.isNullOrBlank()) {
                        RepairResult(false, "No file path was identified for this repair.")
                    } else {
                        val file = File(path)
                        // Safety: only ever delete files inside the launcher's own game folder —
                        // never something a matched path outside it might point at.
                        val gameRoot = File(PathManager.DIR_GAME_HOME).canonicalFile
                        val target = runCatching { file.canonicalFile }.getOrNull()
                        if (target == null || !target.path.startsWith(gameRoot.path)) {
                            RepairResult(false, "Refused to delete a file outside the game folder: $path")
                        } else if (!target.exists()) {
                            RepairResult(false, "That file no longer exists — it may already have been removed.")
                        } else if (target.delete()) {
                            RepairResult(true, "Deleted ${target.name}. Relaunch to let it be re-downloaded/regenerated.")
                        } else {
                            RepairResult(false, "Couldn't delete ${target.name} — check storage permissions.")
                        }
                    }
                }

                RepairActionType.LOWER_RAM_ALLOCATION, RepairActionType.INCREASE_RAM_ALLOCATION -> {
                    val current = AllSettings.ramAllocation.value.getValue()
                    val delta = if (action.type == RepairActionType.LOWER_RAM_ALLOCATION) -RAM_STEP_MB else RAM_STEP_MB
                    val updated = (current + delta).coerceIn(MIN_RAM_MB, MAX_RAM_MB)
                    AllSettings.ramAllocation.value.put(updated).save()
                    RepairResult(true, "RAM allocation changed from ${current}MB to ${updated}MB.")
                }

                // ── Self-Healing Launcher (roadmap #9) ────────────────────────────

                RepairActionType.FIX_PERMISSIONS -> {
                    // Deliberately NOT a recursive chmod of the whole game folder (could be
                    // gigabytes of worlds/resource packs the app already owns and already has
                    // correct permissions on) — scoped to the actual spots an Android storage
                    // permission glitch tends to hit: the natives cache, this version's mods/
                    // config, the app cache, and the pinned Java runtime's own files (which
                    // need their +x bit specifically, since a copy/extract can drop it).
                    val targets = mutableListOf<File>()
                    gameVersion?.let { v ->
                        targets += File(PathManager.DIR_CACHE, "natives/${v.getVersionName()}")
                        targets += File(v.getGameDir(), "mods")
                        targets += File(v.getGameDir(), "config")
                        val pinnedRuntime = v.getVersionConfig().getJavaDir()
                            .takeIf { it.isNotEmpty() && it.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX) }
                            ?.removePrefix(Tools.LAUNCHERPROFILES_RTPREFIX)
                        pinnedRuntime?.let { name ->
                            runCatching { MultiRTUtils.getRuntimeHome(name) }.getOrNull()?.let { targets += it }
                        }
                    }
                    if (PathManager.DIR_APP_CACHE.isDirectory) targets += PathManager.DIR_APP_CACHE

                    var fixedCount = 0
                    targets.filter { it.isDirectory }.forEach { dir ->
                        dir.walkTopDown().forEach { f ->
                            runCatching {
                                if (f.isDirectory) {
                                    f.setReadable(true, false)
                                    f.setExecutable(true, false)
                                } else {
                                    f.setReadable(true, false)
                                    f.setWritable(true, false)
                                    // Native libs and the java binary itself need +x specifically;
                                    // anything that was already executable keeps that bit too.
                                    if (f.extension == "so" || f.name == "java" || f.canExecute()) {
                                        f.setExecutable(true, false)
                                    }
                                }
                                fixedCount++
                            }
                        }
                    }
                    RepairResult(true, "Reset permissions on $fixedCount file(s) across the natives cache, mods, config, app cache, and Java runtime folders.")
                }

                RepairActionType.RESTORE_CONFIGS -> {
                    if (gameVersion == null) {
                        RepairResult(false, "No active game version to restore configs for.")
                    } else {
                        val gameDir = gameVersion.getGameDir()
                        val candidates = listOf("options.txt", "servers.dat", "launcher_profiles.json", "optionsof.txt")
                        var restored = 0
                        candidates.forEach { name ->
                            val f = File(gameDir, name)
                            if (f.exists() && isConfigFileCorrupted(f) && f.delete()) restored++
                        }
                        if (restored > 0) {
                            RepairResult(true, "Removed $restored corrupted config file(s) — Minecraft will regenerate clean defaults on next launch.")
                        } else {
                            RepairResult(true, "No corrupted top-level config files found; nothing needed restoring.")
                        }
                    }
                }

                RepairActionType.VERIFY_GAME_FILES -> {
                    if (gameVersion == null) {
                        RepairResult(false, "No active game version to verify.")
                    } else {
                        val versionName = gameVersion.getVersionName()
                        val listedVersion = runCatching { AsyncMinecraftDownloader.getListedVersion(versionName) }.getOrNull()
                        val latch = CountDownLatch(1)
                        val failure = AtomicReference<Throwable?>(null)
                        // Reuses the exact same hash-verified client-jar/library/asset downloader
                        // the normal launch path calls (see LaunchGame.preLaunch) — wrapped with a
                        // latch since that API is async/callback-based and executeRepair needs to
                        // return synchronously. Nothing here re-implements the verification itself.
                        MinecraftDownloader().start(
                            listedVersion, versionName,
                            object : AsyncMinecraftDownloader.DoneListener {
                                override fun onDownloadDone() { latch.countDown() }
                                override fun onDownloadFailed(throwable: Throwable) { failure.set(throwable); latch.countDown() }
                            }
                        )
                        val completed = latch.await(120, TimeUnit.SECONDS)
                        when {
                            !completed -> RepairResult(false, "Game file verification is still running in the background after 2 minutes — check your connection, or try again once it finishes.")
                            failure.get() != null -> RepairResult(false, "Game file verification failed: ${failure.get()?.message ?: "unknown error"}")
                            else -> RepairResult(true, "Verified the client jar, libraries, and assets for $versionName — anything missing or corrupted was re-downloaded.")
                        }
                    }
                }

                RepairActionType.REPAIR_RUNTIME -> {
                    if (gameVersion == null) {
                        RepairResult(false, "No active game version to repair the runtime for.")
                    } else {
                        val pinnedRuntime = gameVersion.getVersionConfig().getJavaDir()
                            .takeIf { it.isNotEmpty() && it.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX) }
                            ?.removePrefix(Tools.LAUNCHERPROFILES_RTPREFIX)
                        if (pinnedRuntime.isNullOrEmpty()) {
                            RepairResult(true, "This version doesn't pin a specific Java runtime — the launcher default will be auto-verified on next launch.")
                        } else {
                            val runtime = MultiRTUtils.read(pinnedRuntime)
                            if (runtime.javaVersion != 0) {
                                RepairResult(true, "Runtime '$pinnedRuntime' looks intact (Java ${runtime.javaVersion}) — nothing to repair.")
                            } else {
                                runCatching { MultiRTUtils.removeRuntimeNamed(pinnedRuntime) }
                                gameVersion.getVersionConfig().apply { setJavaDir("") }.save()
                                RepairResult(true, "Removed broken runtime '$pinnedRuntime' and cleared this version's pinned Java — a working one will be reinstalled automatically on next launch.")
                            }
                        }
                    }
                }
            }
        }.getOrElse { e -> RepairResult(false, "Repair failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Zero-byte, or (for json/dat) fails a basic parse/magic-number check. options.txt is
     *  plain key=value text with no reliable "corrupted" signature beyond zero-byte, so it's
     *  only caught by that case — a malformed line in it doesn't stop Minecraft from loading. */
    private fun isConfigFileCorrupted(file: File): Boolean = runCatching {
        if (file.length() == 0L) return@runCatching true
        when (file.extension.lowercase()) {
            "json" -> {
                val text = file.readText()
                runCatching { org.json.JSONObject(text) }.isFailure && runCatching { org.json.JSONArray(text) }.isFailure
            }
            "dat" -> RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(2)
                raf.read(header) < 2 || header[0] != 0x1f.toByte() || header[1] != 0x8b.toByte()
            }
            else -> false
        }
    }.getOrDefault(false)

    // ── Export diagnostics ─────────────────────────────────────────────────────

    /**
     * Writes [diagnoses] plus device/version context and a tail of [logText] to a timestamped
     * text file under the game folder's "diagnostics" subfolder, for the user to share (bug
     * report, Discord support channel, etc). Returns the written file, or null on failure.
     */
    @JvmStatic
    @JvmOverloads
    fun exportDiagnostics(
        diagnoses: List<Diagnosis>,
        exitCode: Int? = null,
        logText: String = "",
        gameVersion: Version? = null
    ): File? {
        return runCatching {
            val dir = File(PathManager.DIR_GAME_HOME, "diagnostics").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(dir, "crash_diagnostics_$stamp.txt")
            file.writeText(buildString {
                appendLine("TurtleLauncher Crash Diagnostics Export")
                appendLine("Generated: ${java.util.Date()}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                gameVersion?.let {
                    appendLine("Minecraft version: ${it.getVersionName()}")
                    appendLine("Renderer: ${runCatching { it.getRenderer() }.getOrDefault("?")}")
                    appendLine("Java: ${runCatching { it.getJavaDir() }.getOrDefault("?")}")
                }
                appendLine()
                appendLine(if (diagnoses.isEmpty()) "No diagnosis available." else formatForDisplay(diagnoses, exitCode))
                if (logText.isNotBlank()) {
                    appendLine()
                    appendLine("── Raw log tail ──")
                    appendLine(logText.takeLast(20_000))
                }
            })
            file
        }.getOrNull()
    }

    // ── Search online for known fixes ──────────────────────────────────────────

    /** Builds a search query for [diagnosis] aimed at surfacing known fixes for this exact issue. */
    @JvmStatic
    fun onlineSearchQuery(diagnosis: Diagnosis): String =
        "${diagnosis.title} Minecraft Android TurtleLauncher fix"

    /**
     * Builds a ready-to-open web search URL for [diagnosis]. The UI is responsible for
     * actually opening it (e.g. `startActivity(Intent(ACTION_VIEW, Uri.parse(url)))`) —
     * CrashAnalyzer has no Activity context to do that itself.
     */
    @JvmStatic
    fun onlineSearchUrl(diagnosis: Diagnosis): String {
        val query = java.net.URLEncoder.encode(onlineSearchQuery(diagnosis), "UTF-8")
        return "https://www.google.com/search?q=$query"
    }

    /**
     * Convenience entry point for the post-exit crash flow: reads the launcher's
     * latestlog.txt plus (if [gameVersion] is known) the newest crash-report file for
     * that version, runs [analyze] on the combined text, and returns ready-to-display
     * text. Never throws — any failure just results in an empty string.
     */
    @JvmStatic
    fun analyzeGameExit(gameVersion: Version?, exitCode: Int): String {
        return runCatching {
            val logTail = tailOf(File(PathManager.DIR_GAME_HOME, "latestlog.txt"), MAX_LOG_BYTES)

            val crashReportText = gameVersion?.let { version ->
                runCatching {
                    val crashDir = File(version.getGameDir(), "crash-reports")
                    crashDir.takeIf { it.isDirectory }
                        ?.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                        ?.maxByOrNull { it.lastModified() }
                        ?.let { tailOf(it, MAX_CRASH_REPORT_BYTES) }
                }.getOrNull()
            }

            val ruleDiagnoses = analyze(logTail, crashReportText, gameVersion)
            val conflictDiagnoses = modConflictDiagnoses(gameVersion)
            // Mod-conflict findings are concrete and specific to *this* install, so they lead;
            // if a rule ALSO matched (e.g. a generic mixin_failure), both still show — they're
            // complementary, not competing explanations.
            val withConflicts = conflictDiagnoses + ruleDiagnoses
            val diagnoses = withAiFallback(
                withConflicts.ifEmpty { ruleDiagnoses },
                buildString { append(logTail); if (!crashReportText.isNullOrBlank()) { append("\n"); append(crashReportText) } }
            )
            // Overwrite with the final list (conflicts + AI fallback included) so the "last
            // analysis" the UI reads back for repair/export/search-online reflects everything
            // actually shown, not just the rule-engine subset analyze() saw on its own.
            lastDiagnoses = diagnoses
            lastGameVersion = gameVersion

            val formatted = if (diagnoses.isEmpty()) "" else formatForDisplay(diagnoses, exitCode)
            if (diagnoses.isNotEmpty()) {
                pushCrashHistory(exitCode, diagnoses.first().title)
            }
            formatted
        }.getOrDefault("")
    }
}
