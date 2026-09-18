package com.endiq.turtlelauncher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentAiChatBinding
import com.endiq.turtlelauncher.feature.ai.AssistantHistory
import com.endiq.turtlelauncher.feature.ai.TurtleAssistant
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.subassembly.aichat.ChatMessage
import com.endiq.turtlelauncher.ui.subassembly.aichat.ChatMessageAdapter
import com.endiq.turtlelauncher.utils.ZHTools
import java.io.File

class AiChatFragment : FragmentWithAnim(R.layout.fragment_ai_chat) {
    companion object {
        const val TAG = "AiChatFragment"

        /** Bundle arg carrying a log file shared via the Android share sheet; the
         *  Assistant reads and analyzes it as soon as the conversation is restored. */
        const val ARG_SHARED_LOG_PATH = "shared_log_path"
    }

    private lateinit var binding: FragmentAiChatBinding
    private lateinit var chatAdapter: ChatMessageAdapter

    private val transcript = mutableListOf<ChatMessage>()

    /** Guards against a second question being fired while one is still being answered -
     *  the adapter would otherwise interleave two conversations. */
    private var isAnswering = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentAiChatBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        chatAdapter = ChatMessageAdapter()
        binding.chatMessageList.layoutManager = LinearLayoutManager(requireContext())
        binding.chatMessageList.adapter = chatAdapter

        binding.backButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
        binding.clearButton.setOnClickListener { confirmClear() }

        binding.chatSendButton.setOnClickListener { sendCurrentInput() }
        binding.chatMessageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else false
        }

        restoreConversation()

        // Arrived here because the user shared a game log with the launcher through the
        // Android share sheet? Analyze it right away instead of waiting to be asked.
        val sharedLogPath = arguments?.getString(ARG_SHARED_LOG_PATH)
        if (sharedLogPath != null) {
            arguments?.remove(ARG_SHARED_LOG_PATH)
            analyzeSharedLogFile(File(sharedLogPath))
        }
    }

    /** Loads the saved transcript if there is one, otherwise opens with the greeting. */
    private fun restoreConversation() {
        val saved = runCatching { AssistantHistory.load() }
            .onFailure { e -> Logging.e(TAG, "Couldn't restore assistant history", e) }
            .getOrDefault(emptyList())

        if (saved.isEmpty()) {
            appendMessage(greetingMessage())
            setSuggestions(TurtleAssistant.startingSuggestions())
        } else {
            saved.forEach { appendMessage(it) }
            setSuggestions(TurtleAssistant.startingSuggestions())
        }
    }

    /**
     * Reads a log file handed over via the Android share sheet and lets the Assistant
     * analyze it on a background thread (reading + rule matching can touch the disk).
     * The result is appended to the conversation like any normal answer.
     */
    private fun analyzeSharedLogFile(logFile: File) {
        appendMessage(ChatMessage(getString(R.string.assistant_share_received), true))
        isAnswering = true
        binding.chatSendButton.isEnabled = false
        setSuggestions(emptyList())

        val appContext = requireContext().applicationContext
        TaskExecutors.getDefault().execute {
            val logText = runCatching {
                if (logFile.isFile) logFile.readText() else ""
            }.onFailure { e -> Logging.e(TAG, "Couldn't read the shared log file", e) }
                .getOrDefault("")

            val reply = runCatching { TurtleAssistant.analyzeSharedLog(appContext, logText) }
                .onFailure { e -> Logging.e(TAG, "Assistant failed to analyze the shared log", e) }
                .getOrDefault(TurtleAssistant.Reply(fallbackErrorText()))

            TaskExecutors.runInUIThread {
                if (!isAdded || view == null) return@runInUIThread
                isAnswering = false
                binding.chatSendButton.isEnabled = true
                appendMessage(ChatMessage(reply.text, false))
                setSuggestions(
                    if (reply.suggestions.isNotEmpty()) reply.suggestions
                    else TurtleAssistant.startingSuggestions()
                )
                persist()
            }
        }
    }

    private fun greetingMessage(): ChatMessage =
        ChatMessage(TurtleAssistant.greeting(requireContext()).text, false)

    private fun sendCurrentInput() {
        val text = binding.chatMessageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || isAnswering) return

        binding.chatMessageInput.text?.clear()
        appendMessage(ChatMessage(text, true))

        isAnswering = true
        binding.chatSendButton.isEnabled = false
        setSuggestions(emptyList())

        val appContext = requireContext().applicationContext
        TaskExecutors.getDefault().execute {
            val reply = runCatching { TurtleAssistant.respond(appContext, text) }
                .onFailure { e -> Logging.e(TAG, "Assistant failed to answer", e) }
                .getOrDefault(TurtleAssistant.Reply(fallbackErrorText()))

            TaskExecutors.runInUIThread {
                // A slow answer can land after the user pressed back - touching binding
                // after onDestroyView would crash, so check the fragment is still live.
                if (!isAdded || view == null) return@runInUIThread
                isAnswering = false
                binding.chatSendButton.isEnabled = true
                appendMessage(ChatMessage(reply.text, false))
                setSuggestions(
                    if (reply.suggestions.isNotEmpty()) reply.suggestions
                    else TurtleAssistant.startingSuggestions()
                )
                persist()
            }
        }
    }

    /** Single funnel for "a message is now visible", so the adapter and [transcript] can't drift. */
    private fun appendMessage(message: ChatMessage) {
        transcript.add(message)
        chatAdapter.addMessage(message)
        val last = chatAdapter.itemCount - 1
        if (last >= 0) binding.chatMessageList.scrollToPosition(last)
    }

    private fun setSuggestions(suggestions: List<String>) {
        val row = binding.suggestionRow
        row.removeAllViews()
        if (suggestions.isEmpty()) {
            binding.suggestionScroller.visibility = View.GONE
            return
        }
        binding.suggestionScroller.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val horizontalPadding = (12 * density).toInt()
        val verticalPadding = (7 * density).toInt()
        val chipMarginEnd = (8 * density).toInt()
        val textColor = requireContext().getColor(R.color.turtle_text_primary)

        suggestions.forEach { suggestion ->
            val chip = TextView(requireContext())
            chip.text = suggestion
            chip.textSize = 12f
            chip.setTextColor(textColor)
            chip.setBackgroundResource(R.drawable.background_grid_card)
            chip.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = chipMarginEnd
            chip.layoutParams = params
            chip.setOnClickListener { ask(suggestion) }
            row.addView(chip)
        }
    }

    /** Same path as typing the text and pressing send, so chips and free text behave alike. */
    private fun ask(text: String) {
        if (isAnswering) return
        binding.chatMessageInput.setText(text)
        sendCurrentInput()
    }

    private fun confirmClear() {
        TipDialog.Builder(requireActivity())
            .setTitle(R.string.ai_chat_clear_title)
            .setMessage(getString(R.string.ai_chat_clear_message))
            .setWarning()
            .setConfirm(R.string.generic_clear)
            .setCancel(R.string.cancel)
            .setConfirmClickListener { _ ->
                runCatching { AssistantHistory.clear() }
                    .onFailure { e -> Logging.e(TAG, "Couldn't clear assistant history", e) }
                chatAdapter.clear()
                transcript.clear()
                appendMessage(greetingMessage())
                setSuggestions(TurtleAssistant.startingSuggestions())
                persist()
            }
            .showDialog()
    }

    /** Writes the transcript out; a no-op when AllSettings.aiAssistantHistoryEnabled is off. */
    private fun persist() {
        if (!runCatching { AllSettings.aiAssistantHistoryEnabled.getValue() }.getOrDefault(true)) return
        val snapshot = transcript.toList()
        TaskExecutors.getDefault().execute {
            runCatching { AssistantHistory.save(snapshot) }
                .onFailure { e -> Logging.e(TAG, "Couldn't save assistant history", e) }
        }
    }

    /** getString() itself throws if the fragment is detached - the error path shouldn't be
     *  able to turn one failure into a second one. */
    private fun fallbackErrorText(): String =
        runCatching { getString(R.string.ai_chat_error_generic) }
            .getOrDefault("Something went wrong while answering that. Try again.")
}
