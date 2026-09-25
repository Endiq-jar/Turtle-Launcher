package com.endiq.turtlelauncher.presentation.assistant

import android.content.Context
import android.os.Build
import com.endiq.turtlelauncher.presentation.util.crash.CrashLogParser
import java.io.File
import java.util.Locale

/**
 * Turtle's offline assistant contract adapted to Turtle's repositories.
 *
 * It intentionally does not call a cloud model or require an API key. Answers are
 * deterministic, based on launcher capabilities and the optional local crash log;
 * an unknown question is reported as unknown rather than invented.
 */
object TurtleAssistant {
    data class Reply(val text: String, val suggestions: List<String> = emptyList())

    fun greeting(): Reply = Reply(
        "Hi! I am the launcher assistant. I work on this device without an account or API key. " +
            "Ask about renderers, memory, controls, mods, accounts, LAN, or a crash log.",
        listOf("Status", "Best renderer?", "Why did my game crash?", "Not enough RAM?"),
    )

    fun respond(context: Context, input: String, logText: String? = null): Reply {
        val raw = input.trim()
        if (raw.isEmpty()) return Reply("Type a question or choose a suggestion.", suggestions())
        return when (raw.lowercase(Locale.ROOT)) {
            "/help", "help" -> help()
            "/status", "status" -> status(context)
            "/diagnose", "diagnose", "why did my game crash?" -> diagnose(logText)
            "/renderer", "/renderers", "best renderer?" -> Reply(
                "Start with MobileGlues for the 26.3 SDL path when it is available. " +
                    "Use LTW when you need its OpenGL ES bridge, Zink for Vulkan shader workloads, " +
                    "and GL4ES for older or very low-end devices.",
                listOf("Not enough RAM?", "Status"),
            )
            "/tips", "not enough ram?" -> Reply(
                "On low-end devices leave headroom for Android: lower the Java heap, render distance, " +
                    "resolution scale, and shader quality before raising memory. Keep one renderer " +
                    "selected instead of loading several native stacks.",
                listOf("Best renderer?", "Status"),
            )
            else -> keywordReply(raw.lowercase(Locale.ROOT), logText)
        }
    }

    fun suggestions(): List<String> = listOf(
        "Status", "Best renderer?", "Why did my game crash?", "Not enough RAM?",
        "How do I install mods?", "Controls", "Friends / LAN",
    )

    private fun keywordReply(input: String, logText: String?): Reply = when {
        input.contains("crash") || input.contains("error") -> diagnose(logText)
        input.contains("mod") || input.contains("pack") -> Reply(
            "Open Modpacks from the home menu to search CurseForge or Modrinth. " +
                "The same browser supports mods, resource packs, shader packs, datapacks, and worlds; " +
                "choose an installed instance before installing content.",
            listOf("Best renderer?", "Status"),
        )
        input.contains("control") || input.contains("keyboard") || input.contains("gamepad") -> Reply(
            "Use Keyboard editor to place the on-screen controls. Hardware keyboards and gamepads " +
                "are handled by the runtime; keep the editor profile small on low-end devices.",
            listOf("Status"),
        )
        input.contains("lan") || input.contains("friend") -> Reply(
            "Terracotta provides the launcher's online-LAN path. Open Terracotta from the home menu, " +
                "select a node/profile, and grant the Android VPN consent when requested.",
            listOf("Status"),
        )
        else -> Reply(
            "I do not know that yet. I can explain renderers, crashes, memory, controls, content packs, " +
                "accounts, and Terracotta LAN. Try /help for the complete list.",
            suggestions(),
        )
    }

    private fun help() = Reply(
        "I can help with renderers, SDL/LWJGL compatibility, crash logs, memory, performance, " +
            "controls, mods, resource packs, shader packs, worlds, accounts, versions, files, " +
            "and Terracotta LAN. Commands: /status, /diagnose, /renderer, /tips.",
        suggestions(),
    )

    private fun status(context: Context): Reply {
        val memoryMb = (Runtime.getRuntime().maxMemory() / 1024 / 1024).coerceAtLeast(0)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"
        return Reply(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android: ${Build.VERSION.SDK_INT}\n" +
                "ABI: $abi\n" +
                "Launcher process heap ceiling: ${memoryMb} MB\n" +
                "Runtime features: Minecraft 26.3 SDL path, LTW, MobileGlues, content packs, " +
                "Terracotta, crash reports, and offline assistant.",
            listOf("Best renderer?", "Not enough RAM?"),
        )
    }

    private fun diagnose(logText: String?): Reply {
        if (logText.isNullOrBlank()) {
            return Reply(
                "There is no crash log open. Open Logs/Crash report first, then ask again or share the " +
                    "log with this assistant; I will show only rules that match it.",
                listOf("Status", "Best renderer?"),
            )
        }
        val tail = logText.takeLast(200_000)
        val suspects = CrashLogParser.parseSuspects(tail, File("."))
        return if (suspects.isEmpty()) Reply(
            "I read the log but found no identifiable third-party jar. Check the first ERROR/FATAL " +
                "line and the renderer/native section; I will not guess beyond the evidence.",
            listOf("Status", "Best renderer?"),
        ) else Reply(
            "The log mentions these non-core jars near the failure: " +
                suspects.take(5).joinToString { it.displayName } + ". Disable one at a time and retry; " +
                "the list is evidence, not proof of the root cause.",
            listOf("Best renderer?", "Status"),
        )
    }
}
