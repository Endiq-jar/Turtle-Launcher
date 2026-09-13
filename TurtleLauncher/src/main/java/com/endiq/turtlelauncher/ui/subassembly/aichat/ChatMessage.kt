package com.endiq.turtlelauncher.ui.subassembly.aichat

/** One message in the AI Chat screen - either from the user or the AI. */
data class ChatMessage(
    val text: String,
    val isUser: Boolean
)
