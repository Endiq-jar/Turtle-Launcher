package com.endiq.turtlelauncher.feature.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.subassembly.aichat.ChatMessage
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File

/**
 * On-device persistence for the built-in AI Assistant's conversation
 * (see [TurtleAssistant] and ui/fragment/AiChatFragment.kt).
 *
 * A plain JSON file under the app's own private files dir - no cloud, no account, nothing
 * leaves the device. It's opt-out via AllSettings.aiAssistantHistoryEnabled: with that off,
 * [save] is a no-op and [load] returns empty, so every session starts from the greeting.
 *
 * Hand-rolled JsonArray/JsonObject instead of `Gson().toJson(List<ChatMessage>)` on purpose:
 * reflective (de)serialization of a Kotlin data class needs the field names to survive
 * shrinking, and this project builds a minified "proguard" variant (see build.gradle.kts).
 * Writing the two fields explicitly keeps it working with or without keep rules - same
 * approach CrashAnalyzer's own crash-history/custom-rules JSON uses.
 *
 * Capped at [MAX_MESSAGES] so a long-running conversation can't grow the file without bound;
 * the oldest messages are dropped first.
 */
object AssistantHistory {

    private const val TAG = "AssistantHistory"
    private const val MAX_MESSAGES = 200

    private fun historyFile(): File = File(File(PathManager.DIR_FILE, "ai_chat"), "history.json")

    @JvmStatic
    fun load(): List<ChatMessage> {
        if (!runCatching { AllSettings.aiAssistantHistoryEnabled.getValue() }.getOrDefault(true)) {
            return emptyList()
        }
        return runCatching {
            val file = historyFile()
            if (!file.isFile) return@runCatching emptyList()
            val text = file.readText()
            if (text.isBlank()) return@runCatching emptyList()
            val array = JsonParser.parseString(text).asJsonArray
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val msgText = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return@mapNotNull null
                val isUser = obj.get("user")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                ChatMessage(msgText, isUser)
            }
        }.onFailure { e ->
            // A corrupt/partial file (process killed mid-write is the realistic case) must
            // not stop the screen from opening - drop it and start clean.
            Logging.w(TAG, "Couldn't read assistant history, starting a fresh conversation", e)
            runCatching { historyFile().delete() }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun save(messages: List<ChatMessage>) {
        if (!runCatching { AllSettings.aiAssistantHistoryEnabled.getValue() }.getOrDefault(true)) return
        runCatching {
            val trimmed = if (messages.size > MAX_MESSAGES) messages.takeLast(MAX_MESSAGES) else messages
            val array = JsonArray()
            trimmed.forEach { message ->
                val obj = JsonObject()
                obj.addProperty("text", message.text)
                obj.addProperty("user", message.isUser)
                array.add(obj)
            }
            val file = historyFile()
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }.onFailure { e -> Logging.e(TAG, "Couldn't save assistant history", e) }
    }

    @JvmStatic
    fun clear() {
        runCatching {
            val file = historyFile()
            if (file.exists()) file.delete()
        }.onFailure { e -> Logging.e(TAG, "Couldn't clear assistant history", e) }
    }
}
