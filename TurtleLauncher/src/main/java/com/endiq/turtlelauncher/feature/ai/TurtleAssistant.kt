package com.endiq.turtlelauncher.feature.ai

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.endiq.turtlelauncher.InfoDistributor
import com.endiq.turtlelauncher.feature.log.CrashAnalyzer
import com.endiq.turtlelauncher.feature.log.LatestLogResolver
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.renderer.RendererCatalog
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.util.Locale

/**
 * TurtleLauncher's built-in AI assistant - the thing behind the top-bar Assistant button
 * (see ui/fragment/AiChatFragment.kt).
 *
 * ── Why this is local, and what "AI" means here ─────────────────────────────────────
 *
 * Deliberately needs NO API key, NO account and NO network: everything it knows is either
 * (a) a hand-written topic in [topics] about this launcher, written from what the code in
 * this repo actually does, or (b) read live off the device at answer time (installed
 * versions, active renderer, RAM allocation, free storage, last crash diagnosis). That is
 * the honest description of it: a retrieval/intent engine over a curated launcher
 * knowledge base plus live device state - not a language model. It cannot answer things
 * outside that knowledge base, and [unknownAnswer] says so plainly instead of inventing
 * an answer, which is the failure mode a small on-device "AI" must not have.
 *
 * This launcher already has two *optional* real-LLM features that DO need a user-supplied
 * key ([com.endiq.turtlelauncher.feature.log.AiCrashAdvisor] for crash help and
 * [com.endiq.turtlelauncher.feature.skin.AiContentModerator] for skin filtering, both
 * gated on AllSettings.aiApiKey). They stay separate on purpose: a key must never be a
 * prerequisite for the assistant to work, so nothing here calls them and nothing here
 * reads aiApiKey.
 *
 * ── Threading ───────────────────────────────────────────────────────────────────────
 *
 * [respond] can touch the filesystem (crash log tail, storage stats, version list), so it
 * is treated like every other potentially-slow call in this launcher: AiChatFragment runs
 * it on TaskExecutors.getDefault() and posts the result back to the UI thread.
 */
object TurtleAssistant {

    private const val TAG = "TurtleAssistant"

    /** One topic the assistant can answer. [keywords] drive matching (see [score]);
     *  [answer] builds the reply text, and may read live device/launcher state. */
    private class Topic(
        val id: String,
        /** Short label shown as a tappable suggestion chip. */
        val label: String,
        val keywords: List<String>,
        val answer: (Context) -> String,
        /** Follow-up suggestion chips offered after this topic is answered. */
        val followUps: List<String> = emptyList()
    )

    /** A finished assistant turn: the text to show plus optional suggestion chips. */
    data class Reply(val text: String, val suggestions: List<String> = emptyList())

    /** First message shown when a conversation starts. */
    @JvmStatic
    fun greeting(context: Context): Reply {
        val appName = runCatching { InfoDistributor.APP_NAME }.getOrDefault("TurtleLauncher")
        return Reply(
            "Hi! I'm the $appName assistant. I run entirely on this device - no account, " +
                "no API key, no internet needed.\n\n" +
                "Ask me about renderers, crashes, memory, controls, mods, skins, accounts, " +
                "friends/LAN play, or type /status for a summary of this device and your " +
                "current setup.\n\n" +
                "You can also share a game log with me: use Android's share menu on any " +
                "log file or log text and pick this launcher - I'll read it and tell you " +
                "what went wrong.\n\n" +
                "I only know about this launcher, so if I can't help I'll say so rather " +
                "than guess.",
            listOf("Status", "Best renderer?", "Why did my game crash?", "Not enough RAM?")
        )
    }

    /** Suggestion chips shown before the user has typed anything. */
    @JvmStatic
    fun startingSuggestions(): List<String> = listOf(
        "Status", "Best renderer?", "Why did my game crash?", "FPS is low",
        "How do I install mods?", "Controls", "Friends / LAN"
    )

    /**
     * Answers [input]. Matching is intentionally dumb and explainable: normalize the
     * question, count how many of each topic's keywords appear in it (weighted by keyword
     * length so "resource pack" beats "pack"), and take the best. A tie or a zero score
     * falls through to [unknownAnswer] / [helpAnswer] rather than picking a random topic.
     */
    @JvmStatic
    fun respond(context: Context, input: String): Reply {
        val raw = input.trim()
        if (raw.isEmpty()) return Reply("Type a question, or tap one of the suggestions.")

        // Slash-commands: explicit, so they always win over keyword matching.
        when (raw.lowercase(Locale.ROOT)) {
            "/help", "/?", "help" -> return helpReply()
            "/status", "status" -> return Reply(statusAnswer(context), listOf("Best renderer?", "Why did my game crash?"))
            "/diagnose", "diagnose" -> return crashReply(context)
            "/renderer", "/renderers" -> return topicReply(context, "renderer")
            "/tips" -> return Reply(tipsAnswer(context), listOf("Best renderer?", "Not enough RAM?"))
            "/about" -> return Reply(aboutAnswer(context))
        }

        val normalized = normalize(raw)
        val scored = topics.map { it to score(normalized, it.keywords) }
            .sortedWith(compareByDescending<Pair<Topic, Int>> { it.second }.thenBy { it.first.id })
        val best = scored.firstOrNull()

        if (best == null || best.second <= 0) {
            return Reply(unknownAnswer(raw), startingSuggestions())
        }
        // A very weak match (a single short keyword) is not worth presenting as an answer -
        // better to say "I don't know" and list what I do know.
        if (best.second < 2) {
            return Reply(unknownAnswer(raw), startingSuggestions())
        }
        return Reply(safeAnswer(context, best.first), best.first.followUps)
    }

    /**
     * Analyzes a log the user handed to the launcher through the Android share menu
     * (ShareReceiverActivity -> AiChatFragment). Runs the exact same [CrashAnalyzer] rule
     * engine the post-crash screen uses over [logText] and reports what matched - this is
     * the destination of "share your game log with the Assistant".
     */
    @JvmStatic
    fun analyzeSharedLog(context: Context, logText: String): Reply {
        if (logText.isBlank()) {
            return Reply(
                "The log you shared was empty, so there's nothing for me to read. " +
                    "If the game crashed, try sharing the log again right after it happens.",
                listOf("Help")
            )
        }
        // Keep the analysis bounded - shared files can be huge and the rule engine only ever
        // needs the tail where the actual failure sits.
        val tail = if (logText.length > 200_000) logText.takeLast(200_000) else logText
        val diagnoses = runCatching { CrashAnalyzer.analyze(tail) }
            .onFailure { e -> Logging.e(TAG, "Failed to analyze a shared log", e) }
            .getOrDefault(emptyList())
        if (diagnoses.isEmpty()) {
            return Reply(
                "I read the log you shared, but it doesn't contain anything my rules can " +
                    "explain (no crash, no error lines I recognize). If the game misbehaved, " +
                    "share the log again right after it happens.",
                listOf("Why did my game crash?", "Status")
            )
        }
        val formatted = runCatching { CrashAnalyzer.formatForDisplay(diagnoses) }.getOrDefault("")
        val text = if (formatted.isNotBlank()) formatted else diagnoses.joinToString("\n\n") {
            "${it.title}\n${it.cause}"
        }
        return Reply(
            "I read the log you shared. Here's what my rules found:\n\n$text",
            listOf("Why did my game crash?", "Best renderer?", "Status")
        )
    }

    private fun topicReply(context: Context, id: String): Reply {
        val topic = topics.firstOrNull { it.id == id } ?: return helpReply()
        return Reply(safeAnswer(context, topic), topic.followUps)
    }

    private fun helpReply(): Reply = Reply(
        "Here's what I can help with:\n" +
            "• Renderers - which one to use, how to change it, shader support\n" +
            "• Crashes - read and explain your last game log\n" +
            "• Memory / RAM - how much to allocate on this device\n" +
            "• Performance - FPS, resolution scale, FPS boost flags\n" +
            "• Mods, modpacks, resource packs, shaders, worlds\n" +
            "• Controls, custom buttons, gamepads\n" +
            "• Skins and capes\n" +
            "• Accounts and login\n" +
            "• Game versions, snapshots, LWJGL compatibility\n" +
            "• Friends / LAN play\n" +
            "• Storage, files, screenshots and recording\n\n" +
            "Commands: /status /diagnose /renderer /tips /about",
        startingSuggestions()
    )

    /** Never let one broken live-state read take the whole assistant down. */
    private fun safeAnswer(context: Context, topic: Topic): String =
        runCatching { topic.answer(context) }
            .onFailure { e -> Logging.e(TAG, "Topic '${topic.id}' failed to answer", e) }
            .getOrDefault("I hit an error while putting that answer together (${topic.id}). " +
                "Try again, or ask something else - /help lists what I know.")

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 /]"), " ")

    private fun score(normalized: String, keywords: List<String>): Int {
        var total = 0
        for (keyword in keywords) {
            val key = keyword.lowercase(Locale.ROOT)
            if (key.isEmpty()) continue
            if (normalized.contains(key)) {
                // Longer keywords are more specific, so they carry more weight.
                total += if (key.length >= 6) 3 else 2
            }
        }
        return total
    }

    private fun unknownAnswer(input: String): String {
        val shown = if (input.length > 60) input.take(60) + "…" else input
        return "I don't have an answer for \"$shown\".\n\n" +
            "I only know about this launcher - I'm not connected to the internet and I " +
            "won't make something up. Try one of these instead, or /help for the full list."
    }

    // ── Live-state answers ──────────────────────────────────────────────────────────

    private fun statusAnswer(context: Context): String {
        val deviceTotalMb = runCatching { Tools.getTotalDeviceMemory(context) }.getOrDefault(0)
        val ramMb = runCatching { AllSettings.ramAllocation.value.getValue() }.getOrDefault(0)
        val rendererName = currentRendererName(context)
        val versions = runCatching { VersionsManager.getVersions() }.getOrDefault(emptyList())
        val current = runCatching { VersionsManager.getCurrentVersion()?.getVersionName() }
            .getOrNull()
        val freeGb = freeStorageGb()
        val launcherVersion = runCatching { ZHTools.getVersionName() }.getOrDefault("?")

        val sb = StringBuilder()
        sb.append("Here's your setup right now:\n")
        sb.append("• Launcher: ${runCatching { InfoDistributor.APP_NAME }.getOrDefault("TurtleLauncher")} v$launcherVersion\n")
        sb.append("• Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})\n")
        if (deviceTotalMb > 0) sb.append("• RAM: ${deviceTotalMb}MB total, ${ramMb}MB allocated to Minecraft\n")
        sb.append("• Renderer: $rendererName\n")
        sb.append("• Versions installed: ${versions.size}" +
            (current?.let { " (selected: $it)" } ?: " (none selected)") + "\n")
        if (freeGb != null) sb.append("• Free storage on the game drive: ${freeGb}GB\n")
        sb.append("• Last crash: ")
        val log = readMostRecentGameLog()
        sb.append(if (log.isNullOrBlank()) "none recorded" else "a log is available - ask me \"why did my game crash?\"")
        return sb.toString()
    }

    /**
     * The freshest game log the assistant can get its hands on.
     *
     * First try [CrashAnalyzer.getLastLogText] - the in-memory tail the crash screen
     * captured - but that only survives in the process that ran the analysis, so fall back
     * to the newest game log file on disk (the same one the home screen's "Last Game Log"
     * card points at, see [LatestLogResolver.resolveLastGameLogFile]). Without this fallback
     * the assistant keeps claiming "no recent game log" in a fresh session even though the
     * log is sitting right there - which is exactly what made log-sharing feel broken.
     */
    private fun readMostRecentGameLog(): String? {
        val inMemory = runCatching { CrashAnalyzer.getLastLogText() }.getOrNull()
        if (!inMemory.isNullOrBlank()) return inMemory
        return runCatching {
            val file = LatestLogResolver.resolveLastGameLogFile() ?: return@runCatching null
            CrashAnalyzer.tailOf(file, 64 * 1024)
        }.getOrNull()
    }

    /** Human-readable name of the renderer this launcher would actually use right now. */
    private fun currentRendererName(context: Context): String = runCatching {
        val wanted = AllSettings.renderer.getValue()
        Renderers.init(false)
        val compatible = Renderers.getCompatibleRenderers(context).second
        val match = compatible.firstOrNull { it.getUniqueIdentifier() == wanted }
        when {
            match != null -> {
                val badge = RendererCatalog.get(match.getRendererId())?.badge
                "${match.getRendererName()}" + (badge?.let { " (${it.name.lowercase(Locale.ROOT)})" } ?: "")
            }
            // Exactly the situation Renderers.setCurrentRenderer(retryToFirstOnFailure=true)
            // resolves at launch time - say so instead of printing a UUID that means nothing.
            compatible.isNotEmpty() ->
                "${compatible[0].getRendererName()} (auto-fallback - the saved choice isn't available on this device)"
            else -> "none available on this device"
        }
    }.getOrDefault("unknown")

    private fun freeStorageGb(): String? = runCatching {
        val path = PathManager.DIR_GAME_HOME
        if (path.isBlank()) return@runCatching null
        val target = File(path).takeIf { it.exists() } ?: File(path).parentFile ?: return@runCatching null
        val stat = StatFs(target.absolutePath)
        val bytes = stat.availableBytes
        String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))
    }.getOrNull()

    /** Reads the most recent game log through the same [CrashAnalyzer] rules the crash
     *  screen uses, then summarizes the result in chat. No network, no key. */
    private fun crashReply(context: Context): Reply {
        val log = readMostRecentGameLog()
        if (log.isNullOrBlank()) {
            return Reply(
                "I don't have a recent game log to look at.\n\n" +
                    "Launch the game once and let it crash (or exit with an error) - the " +
                    "launcher keeps the tail of that log, and I can then read it for you. " +
                    "You can also open the full log from the home screen's \"Last Game Log\" " +
                    "card, or share it via Share Logs.",
                listOf("Status", "Best renderer?")
            )
        }
        val diagnoses = runCatching { CrashAnalyzer.analyze(log) }.getOrDefault(emptyList())
        if (diagnoses.isEmpty()) {
            return Reply(
                "I read the last game log, but none of my built-in rules matched it, so I " +
                    "can't tell you what went wrong with any confidence.\n\n" +
                    "What usually helps next:\n" +
                    "• Share Logs (home screen rail) - uploads the log so someone can read the raw output\n" +
                    "• Settings → Experimental → \"AI crash help\" - optional, sends the log tail to " +
                    "OpenAI with YOUR OWN key, off by default\n" +
                    "• Try a different renderer for this version (Settings → Video → Renderer)",
                listOf("Best renderer?", "Status")
            )
        }
        val formatted = runCatching { CrashAnalyzer.formatForDisplay(diagnoses) }.getOrDefault("")
        val text = if (formatted.isNotBlank()) formatted else diagnoses.joinToString("\n\n") {
            "${it.title}\n${it.cause}"
        }
        return Reply(
            "I read the last game log. Here's what my rules found:\n\n$text",
            listOf("Best renderer?", "Not enough RAM?", "Status")
        )
    }

    private fun tipsAnswer(context: Context): String {
        val ramMb = runCatching { AllSettings.ramAllocation.value.getValue() }.getOrDefault(0)
        return "Quick wins on this device:\n" +
            "• Renderer: ${currentRendererName(context)} - change it in Settings → Video → Renderer\n" +
            "• RAM: ${ramMb}MB allocated (Settings → Game). Leave 2GB+ free for Android itself\n" +
            "• Resolution scale (Settings → Video) is the single biggest FPS lever on phones\n" +
            "• Settings → Optimization has the FPS boost flags; turn them off one at a time if something breaks\n" +
            "• Ask me \"why did my game crash?\" after a crash - I read the log locally"
    }

    private fun aboutAnswer(context: Context): String {
        val versions = runCatching { VersionsManager.getVersions() }.getOrDefault(emptyList())
        return "TurtleLauncher is a TurtleLauncher Minecraft: Java Edition " +
            "launcher for Android.\n\n" +
            "• Version: ${runCatching { ZHTools.getVersionName() }.getOrDefault("?")} " +
            "(code ${runCatching { ZHTools.getVersionCode() }.getOrDefault(0)})\n" +
            "• Installed game versions: ${versions.size}\n" +
            "• Renderer in use: ${currentRendererName(context)}\n\n" +
            "This assistant is part of the launcher itself and works offline - it doesn't " +
            "call any AI service."
    }

    // ── Knowledge base ──────────────────────────────────────────────────────────────
    // Every answer below was written against what this repo's own code does; where a claim
    // comes from upstream (e.g. MobileGlues' behaviour on MC 26.3) the source is named so
    // it can be checked rather than trusted.

    private val topics: List<Topic> = listOf(
        Topic(
            id = "renderer",
            label = "Best renderer?",
            keywords = listOf(
                "renderer", "renderers", "mobileglues", "mobile glues", "gl4es", "zink",
                "virgl", "ltw", "freedreno", "vgpu", "angle", "vulkan", "opengl",
                "graphics backend", "driver", "turnip", "which renderer", "best renderer",
                "shader", "shaders pack", "iris", "optifine"
            ),
            answer = { context ->
                val current = currentRendererName(context)
                val hasVulkan = runCatching {
                    Tools.checkVulkanSupport(context.packageManager)
                }.getOrDefault(false)
                buildString {
                    append("You're currently on: $current\n\n")
                    append("How to change it: Settings → Video → Renderer. The Graphics Backend switch " +
                        "above it is a shortcut - on = OpenGL family, off = Vulkan (Zink).\n\n")
                    append("Rough guide:\n")
                    append("• MobileGlues - this launcher's default. A real GL implementation on top of " +
                        "your own GLES 3.x, self-contained, and the GL-family option that renders shader packs.\n")
                    append("• Zink - Minecraft's GL translated to Vulkan. Best on Adreno/Mali with a working " +
                        "Vulkan driver" + (if (hasVulkan) " (your device does report Vulkan support)." else " - your device doesn't report Vulkan support, so skip it.") + "\n")
                    append("• LTW / Holy GL4ES - GL→GLES translators, the safe fallback when the above glitch. " +
                        "Holy GL4ES is documented up to MC 1.21.4 only.\n")
                    append("• Freedreno (+ Turnip driver) - Adreno-specific.\n")
                    append("• VirGL / VGPU / NW / ANGLE - niche, badged experimental.\n\n")
                    append("One real caveat: Amethyst-Android's release notes list MobileGlues and Krypton " +
                        "Wrapper as crashing on Minecraft 26.3-snapshot4 and above because of how SDL now " +
                        "creates its EGL window. On those versions, try Zink or LTW instead.")
                }
            },
            followUps = listOf("Shaders not working", "Why did my game crash?", "Status")
        ),
        Topic(
            id = "shader",
            label = "Shaders not working",
            keywords = listOf("shader", "shaders", "iris", "optifine", "shader pack", "shadow"),
            answer = {
                "Shader packs only render on some renderers. In this launcher, RendererCatalog marks " +
                    "MobileGlues, VirGL and Zink as shader-capable (that mapping comes from " +
                    "FCL-Team/FoldCraftLauncher's own README).\n\n" +
                    "So: switch to MobileGlues or Zink in Settings → Video → Renderer, install Iris " +
                    "(or OptiFine) for your exact game version from the Download screen, then put the " +
                    "shader pack in the instance's shaderpacks folder (Files screen). If a pack renders " +
                    "black or crashes, that's usually the pack needing a GL feature the renderer doesn't " +
                    "implement - try a lighter pack before blaming the launcher."
            },
            followUps = listOf("Best renderer?", "How do I install mods?")
        ),
        Topic(
            id = "crash",
            label = "Why did my game crash?",
            keywords = listOf(
                "crash", "crashed", "crashing", "exit code", "sigsegv", "signal", "error log",
                "why did my game", "game closes", "force close", "freeze", "frozen", "stuck",
                "black screen", "diagnose", "hs_err", "anr"
            ),
            answer = { context -> crashReply(context).text },
            followUps = listOf("Best renderer?", "Not enough RAM?", "Status")
        ),
        Topic(
            id = "memory",
            label = "Not enough RAM?",
            keywords = listOf(
                "ram", "memory", "heap", "allocation", "xmx", "out of memory", "oom",
                "outofmemory", "lag", "how much ram", "gc"
            ),
            answer = { context ->
                val deviceTotalMb = runCatching { Tools.getTotalDeviceMemory(context) }.getOrDefault(0)
                val ramMb = runCatching { AllSettings.ramAllocation.value.getValue() }.getOrDefault(0)
                buildString {
                    append("This device has ${deviceTotalMb}MB of RAM in total, and Minecraft is currently " +
                        "allocated ${ramMb}MB (Settings → Game → RAM allocation).\n\n")
                    append("Rules of thumb:\n")
                    append("• Vanilla: 1024-2048MB is plenty. Modpacks: 3072-4096MB.\n")
                    append("• Never allocate more than about half of the device total - Android kills the " +
                        "game (or the launcher) when memory gets tight, which looks exactly like a random crash.\n")
                    append("• More RAM does not add FPS. If you're stuttering, lower the resolution scale " +
                        "first (Settings → Video).\n")
                    append("• Settings → Optimization → Auto RAM Calculator keeps this value tuned to the device.")
                }
            },
            followUps = listOf("FPS is low", "Status")
        ),
        Topic(
            id = "performance",
            label = "FPS is low",
            keywords = listOf(
                "fps", "slow", "laggy", "performance", "frame", "frames", "stutter", "boost",
                "speed up", "optimize", "optimise", "heat", "hot", "thermal", "battery"
            ),
            answer = { context ->
                buildString {
                    append("In rough order of how much they actually help on a phone:\n")
                    append("1. Settings → Video → Resolution scale - drop it to 80-90%. Biggest single win.\n")
                    append("2. Renderer choice - ask me \"best renderer?\"; the wrong one can cost half your FPS.\n")
                    append("3. Settings → Optimization - the FPS boost flags (frame skipping, adaptive frame timing).\n")
                    append("4. Settings → Video → Sustained performance - stops the CPU throttling down mid-session, " +
                        "at the cost of more heat/battery.\n")
                    append("5. In-game: render distance down, and a performance mod (Sodium/Fabric or Embeddium) " +
                        "from the Download screen.\n\n")
                    append("Current renderer: ${currentRendererName(context)}.")
                }
            },
            followUps = listOf("Best renderer?", "Not enough RAM?")
        ),
        Topic(
            id = "mods",
            label = "How do I install mods?",
            keywords = listOf(
                "mod", "mods", "modpack", "mod pack", "fabric", "forge", "neoforge", "quilt",
                "optifine jar", "install mod", "curseforge", "modrinth", "resource pack",
                "texture pack", "world download", "mrpack"
            ),
            answer = {
                "Mods and packs: top bar → Download.\n" +
                    "• Mods / Modpacks / Resource packs / Shader packs / Worlds are separate tabs there, " +
                    "searching Modrinth and CurseForge.\n" +
                    "• Mod loaders (Fabric, Forge, NeoForge, Quilt, OptiFine, Cleanroom) are installed per " +
                    "version - open the version's manager (the gear next to the version selector on the home " +
                    "screen) and pick your loader.\n" +
                    "• A .mrpack/.zip modpack you already have: home screen rail → Modpack Importer.\n" +
                    "• A loose .jar mod or library: rail → Install JAR.\n" +
                    "• Files screen gets you straight to the instance's mods/ folder.\n\n" +
                    "A mod must match BOTH the game version and the loader version, or it won't load - that's " +
                    "the most common \"mod does nothing\" cause."
            },
            followUps = listOf("Shaders not working", "Which game version?")
        ),
        Topic(
            id = "controls",
            label = "Controls",
            keywords = listOf(
                "control", "controls", "button", "buttons", "joystick", "gamepad", "controller",
                "keyboard", "mouse", "cursor", "touch", "layout", "keybind", "key binding",
                "hotbar", "remap"
            ),
            answer = {
                "Home screen rail → Custom Controls opens the control editor: add/move/resize buttons, " +
                    "joysticks and hotbars, save layouts as presets (a survival preset ships with the launcher).\n\n" +
                "• Settings → Control: sensitivity, control layout picker, gamepad options.\n" +
                "• Settings → Accessibility: button size/opacity, and the notch/ignore-notch options.\n" +
                "• Top bar → Cursor opens the custom mouse/cursor manager (you can import .cur/.ani/.ico files).\n" +
                "• A physical gamepad is detected automatically while the game runs; mapping is in the " +
                "control editor's remapper.\n" +
                "• In-game: the pause menu button opens the launcher's game menu (screenshot, controls, log)."
            },
            followUps = listOf("Screenshots and recording", "FPS is low")
        ),
        Topic(
            id = "skins",
            label = "Skins and capes",
            keywords = listOf("skin", "skins", "cape", "capes", "avatar", "appearance"),
            answer = {
                "Account screen → your account → Skin/Cape. You can browse a built-in gallery, import a " +
                    "64x64 (or 64x32 legacy) PNG from your device, and pick classic/slim arms.\n\n" +
                "With a Microsoft account the skin is applied to your real profile. With a local (offline) " +
                "account it's applied locally through the launcher's own skin server, so only you see it.\n\n" +
                "There's also an optional AI skin filter (Settings → Experimental) that screens gallery " +
                "images - it needs your own API key and is off by default."
            },
            followUps = listOf("Accounts and login", "Status")
        ),
        Topic(
            id = "accounts",
            label = "Accounts and login",
            keywords = listOf(
                "account", "accounts", "login", "log in", "sign in", "microsoft", "offline",
                "premium", "token", "auth", "authlib", "nide8", "session", "demo"
            ),
            answer = {
                "Top bar → Account (the person icon).\n" +
                    "• Microsoft: the real login flow, needed for online play on official servers.\n" +
                    "• Local: an offline username - single player and LAN/offline-mode servers only.\n" +
                    "• Other: third-party auth servers (authlib-injector / nide8auth style) via a custom URL.\n\n" +
                "If a Microsoft login stalls, it's nearly always the device clock being wrong or a browser " +
                    "handoff being cancelled - retry from the Account screen. Being dropped to Demo.Player " +
                    "means the token refresh failed while offline; reconnect and log in again."
            },
            followUps = listOf("Friends / LAN", "Status")
        ),
        Topic(
            id = "versions",
            label = "Which game version?",
            keywords = listOf(
                "version", "versions", "snapshot", "release", "1.21", "26.1", "26.2", "26.3",
                "update minecraft", "lwjgl", "which version", "install version", "old version"
            ),
            answer = {
                "Home screen → the version card (or its dropdown) lists installed versions; Install Game " +
                    "adds new ones, including snapshots and old releases.\n\n" +
                "• Newer isn't automatically better on a phone: mod support and renderer compatibility both " +
                "matter more than the version number.\n" +
                "• Minecraft 26.3+ moved its windowing from GLFW to SDL (org.lwjgl:lwjgl-sdl). This launcher " +
                "detects that per version and swaps in the matching LWJGL native; Settings → Experimental → " +
                "\"LWJGL compatibility mode\" can force new/legacy if a specific version misbehaves.\n" +
                "• If a brand-new snapshot crashes at startup, try the previous release before reporting it - " +
                "and ask me \"why did my game crash?\" so I can read the log."
            },
            followUps = listOf("Best renderer?", "Why did my game crash?")
        ),
        Topic(
            id = "terracotta",
            label = "Friends / LAN",
            keywords = listOf(
                "friend", "friends", "lan", "multiplayer", "terracotta", "join", "host", "room",
                "code", "p2p", "play with friends", "server"
            ),
            answer = {
                "Home screen → Friends/LAN (Terracotta). One player hosts a room and shares the code; the " +
                    "other joins with it. It builds a direct peer-to-peer link (EasyTier based) - no port " +
                    "forwarding - and there's a built-in chat while connected.\n\n" +
                "Known rough edges, so you don't chase them:\n" +
                "• Joining can take a few tries; the launcher retries on its own before showing an error.\n" +
                "• \"Use EasyTier public node\" points at the official public node - if it's unreachable, " +
                "Settings → Terracotta lets you put in your own node address.\n" +
                "• Both players need the same game version and compatible mods."
            },
            followUps = listOf("Accounts and login", "Status")
        ),
        Topic(
            id = "storage",
            label = "Storage and files",
            keywords = listOf(
                "storage", "space", "files", "folder", "directory", "game dir", "profile path",
                "delete", "clean", "cleanup", "cache", "where are", "screenshot folder", "saves"
            ),
            answer = { context ->
                val free = freeStorageGb()
                buildString {
                    append("Top bar → Files opens the game directory; from there you can reach saves/, " +
                        "resourcepacks/, mods/ and the logs.\n\n")
                    if (free != null) append("Free space on that drive right now: ${free}GB.\n")
                    append("• Settings → Launcher → Profile path moves the whole game directory (useful when " +
                        "internal storage is tight).\n")
                    append("• Settings → Experimental has cleanup tools for old caches and logs.\n")
                    append("• Each version keeps its own folder under versions/, so deleting one version " +
                        "doesn't touch the others.")
                }
            },
            followUps = listOf("Screenshots and recording", "Status")
        ),
        Topic(
            id = "recording",
            label = "Screenshots and recording",
            keywords = listOf(
                "screenshot", "screenshots", "record", "recording", "video", "capture", "share log",
                "share logs", "upload log"
            ),
            answer = {
                "While the game runs, the in-game menu button has Screenshot and Record.\n" +
                    "• Settings → Recording: resolution, bitrate and format for the recorder.\n" +
                    "• Screenshots land in the instance's screenshots/ folder (Files screen).\n" +
                    "• Home rail → Share Logs packages the launcher + game logs and gives you a link you " +
                    "can paste into a bug report - that's the fastest way to get help with a crash."
            },
            followUps = listOf("Why did my game crash?", "Storage and files")
        ),
        Topic(
            id = "java",
            label = "Java / runtime",
            keywords = listOf(
                "java", "jre", "jdk", "runtime", "java args", "jvm", "java 8", "java 17",
                "java 21", "class version", "unsupported class"
            ),
            answer = {
                "The launcher ships its own runtimes (8, 17, 21, 25) and picks per version automatically - " +
                    "Settings → Java shows the selection and lets you force one.\n\n" +
                "• \"Unsupported class version\" / \"class file version\" errors mean the wrong runtime was " +
                "picked: newer Minecraft needs 17 or 21, very old versions need 8.\n" +
                "• Custom JVM arguments go in Settings → Java, but a bad argument there stops the game from " +
                "starting at all - if it won't launch after editing them, clear them first.\n" +
                "• A sandbox policy is applied to the game JVM by default (Settings → Java) - some very old " +
                "mods need it turned off."
            },
            followUps = listOf("Not enough RAM?", "Why did my game crash?")
        ),
        Topic(
            id = "shizuku",
            label = "Shizuku",
            keywords = listOf("shizuku", "root", "permission", "adb", "system"),
            answer = {
                "Settings → Shizuku. Shizuku is an optional helper app that lets this launcher do a few " +
                    "things a normal Android app can't - e.g. inspect/manage other apps' data or run shell " +
                    "commands for setup - without rooting your device.\n\n" +
                "It's entirely optional: nothing in the launcher requires it, and every feature that can use " +
                    "it has a normal fallback. If Shizuku isn't installed or isn't running, the screen just " +
                    "tells you and the rest of the launcher carries on."
            },
            followUps = listOf("Status", "Storage and files")
        ),
        Topic(
            id = "updates",
            label = "Updating",
            keywords = listOf(
                "update", "updates", "upgrade", "new version of the launcher", "plugin update",
                "renderer update", "outdated", "beta", "release channel"
            ),
            answer = {
                "The launcher checks for its own updates on start and shows an update dialog when there's " +
                    "one; Settings → Launcher has the release channel.\n\n" +
                "Separately, Settings → Renderer Manager / Experimental can check upstream for renderer and " +
                    "driver plugin updates (for example MobileGlues' own releases) - that only touches " +
                    "downloaded plugins, never the built-in renderers bundled in the APK.\n\n" +
                "You're on version " + runCatching { ZHTools.getVersionName() }.getOrDefault("?") + "."
            },
            followUps = listOf("About", "Status")
        ),
        Topic(
            id = "assistant",
            label = "About this assistant",
            keywords = listOf(
                "assistant", "ai", "chatgpt", "gpt", "chat bot", "are you", "who are you",
                "what are you", "api key", "offline", "privacy", "do you send", "llm"
            ),
            answer = {
                "I'm the launcher's built-in assistant. I'm not a language model and I don't call any AI " +
                    "service: everything I say comes from a knowledge base about this launcher plus live " +
                    "readings from this device, so I work with no API key, no account and no internet.\n\n" +
                "That also means I can't answer general questions, and I won't pretend to. If I don't know, " +
                    "I say so.\n\n" +
                "Two other features in this launcher DO talk to an AI service (crash help and the skin " +
                    "filter, both in Settings → Experimental). They're off by default and need your own key - " +
                    "nothing is sent anywhere unless you turn them on."
            },
            followUps = listOf("Help", "Status")
        ),
        Topic(
            id = "help",
            label = "Help",
            keywords = listOf("help", "commands", "what can you do", "menu", "options"),
            answer = { helpReply().text },
            followUps = startingSuggestions()
        ),
        Topic(
            id = "about",
            label = "About",
            keywords = listOf("about", "turtle launcher", "turtlelauncher", "what is this app", "version of the launcher"),
            answer = { context -> aboutAnswer(context) },
            followUps = listOf("Status", "Updating")
        )
    )
}
