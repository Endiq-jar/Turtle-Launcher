package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentLogViewerBinding
import com.endiq.turtlelauncher.feature.log.CrashAnalyzer
import com.endiq.turtlelauncher.feature.log.LatestLogResolver
import com.endiq.turtlelauncher.feature.log.LogLineStyle
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.log.MclogsUploader
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import java.io.File

class LogViewerFragment : FragmentWithAnim(R.layout.fragment_log_viewer) {
    companion object {
        const val TAG: String = "LogViewerFragment"
        private const val ARG_FILE_PATH = "file_path"
        private const val MAX_READ_BYTES = 512 * 1024

        fun createArgs(file: File): Bundle = Bundle().apply {
            putString(ARG_FILE_PATH, file.absolutePath)
        }
    }

    private lateinit var binding: FragmentLogViewerBinding
    private var allLines: List<String> = emptyList()
    private var currentFile: File? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentLogViewerBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.backButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        val path = arguments?.getString(ARG_FILE_PATH)
        val file = path?.let { File(it) } ?: LatestLogResolver.resolveLatestLogFile()
        currentFile = file

        if (file == null || !file.isFile) {
            binding.logViewerTitle.text = getString(R.string.log_viewer_title)
            binding.logMatchCount.text = getString(R.string.share_logs_none_found)
            setActionButtonsEnabled(false)
            return
        }

        binding.logViewerTitle.text = file.name
        val content = runCatching { CrashAnalyzer.tailOf(file, MAX_READ_BYTES) }.getOrDefault("")
        allLines = content.split("\n")

        binding.logErrorsOnlyCheckbox.setOnCheckedChangeListener { _, _ -> applyFilter() }
        binding.logSearchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        binding.logViewerCopyButton.setOnClickListener { copyLogToClipboard() }
        binding.logViewerShareButton.setOnClickListener { shareLogFile() }
        binding.logViewerUploadButton.setOnClickListener { uploadToMclogs() }

        applyFilter()
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.logViewerCopyButton.isEnabled = enabled
        binding.logViewerShareButton.isEnabled = enabled
        binding.logViewerUploadButton.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        binding.logViewerCopyButton.alpha = alpha
        binding.logViewerShareButton.alpha = alpha
        binding.logViewerUploadButton.alpha = alpha
    }

    private fun copyLogToClipboard() {
        val file = currentFile ?: return
        val content = runCatching { CrashAnalyzer.tailOf(file, 128 * 1024) }.getOrDefault("")
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, content))
        Toast.makeText(requireContext(), R.string.share_logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogFile() {
        val file = currentFile ?: return
        runCatching { FileTools.shareFile(requireContext(), file) }
    }

    private fun uploadToMclogs() {
        val file = currentFile ?: return
        val content = runCatching { CrashAnalyzer.tailOf(file, 512 * 1024) }.getOrDefault("")
        if (content.isBlank()) {
            Toast.makeText(requireContext(), R.string.share_logs_none_found, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), R.string.mclogs_uploading, Toast.LENGTH_SHORT).show()
        Task.runTask {
            MclogsUploader.upload(content)
        }.ended(TaskExecutors.getAndroidUI()) { result ->
            if (!isAdded) return@ended
            when (result) {
                is MclogsUploader.Result.Success -> showMclogsResultDialog(result.url)
                is MclogsUploader.Result.Failure -> Toast.makeText(
                    requireContext(),
                    getString(R.string.mclogs_upload_failed, result.message),
                    Toast.LENGTH_LONG
                ).show()
                null -> Toast.makeText(
                    requireContext(),
                    getString(R.string.mclogs_upload_failed, ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onThrowable { e ->
            Logging.e("LogViewerFragment", "mclo.gs upload task failed", e)
        }.execute()
    }

    private fun showMclogsResultDialog(url: String) {
        TipDialog.Builder(requireContext())
            .setTitle(R.string.mclogs_upload_success_title)
            .setMessage(url)
            .setSelectable(true)
            .setConfirm(R.string.mclogs_open_link)
            .setConfirmClickListener {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
            .setCancel(R.string.mclogs_copy_link)
            .setCancelClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("mclo.gs", url))
                Toast.makeText(requireContext(), R.string.mclogs_link_copied, Toast.LENGTH_SHORT).show()
            }
            .buildDialog()
            .show()
    }

    private fun applyFilter() {
        val query = binding.logSearchEdit.text?.toString()?.trim().orEmpty()
        val errorsOnly = binding.logErrorsOnlyCheckbox.isChecked

        val filtered = allLines.filter { line ->
            (!errorsOnly || LogLineStyle.isError(line)) &&
                (query.isEmpty() || line.contains(query, ignoreCase = true))
        }

        binding.logMatchCount.text = if (allLines.isEmpty() || allLines.all { it.isBlank() }) {
            getString(R.string.share_logs_none_found)
        } else {
            getString(R.string.log_viewer_match_count, filtered.size)
        }

        if (filtered.isEmpty()) {
            binding.logContentText.text = getString(R.string.log_viewer_no_matches)
            return
        }

        val context = requireContext()
        val highlightColor = ContextCompat.getColor(context, R.color.accent_primary)

        val builder = SpannableStringBuilder()
        filtered.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val end = builder.length

            LogLineStyle.apply(context, builder, start, end, line)

            if (query.isNotEmpty()) {
                var searchFrom = 0
                val lowerLine = line.lowercase()
                val lowerQuery = query.lowercase()
                while (true) {
                    val matchIndex = lowerLine.indexOf(lowerQuery, searchFrom)
                    if (matchIndex == -1) break
                    builder.setSpan(
                        BackgroundColorSpan(Color.argb(120, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor))),
                        start + matchIndex,
                        start + matchIndex + query.length,
                        0
                    )
                    searchFrom = matchIndex + query.length
                }
            }

            if (index != filtered.lastIndex) builder.append("\n")
        }
        binding.logContentText.text = builder
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.exit()))
    }
}
