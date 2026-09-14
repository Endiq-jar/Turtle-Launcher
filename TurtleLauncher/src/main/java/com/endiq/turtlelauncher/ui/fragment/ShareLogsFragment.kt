package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentShareLogsBinding
import com.endiq.turtlelauncher.feature.log.CrashAnalyzer
import com.endiq.turtlelauncher.feature.log.LatestLogResolver
import com.endiq.turtlelauncher.feature.log.MclogsUploader
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Share Logs screen: shows the most recent launcher log file plus four actions
 * (View / Share / Copy / Clear), replacing the old single "Share Logs" quick action that
 * instantly zipped and opened the system share sheet with no way to preview or clear logs
 * first.
 */
class ShareLogsFragment : FragmentWithAnim(R.layout.fragment_share_logs) {
    companion object {
        const val TAG: String = "ShareLogsFragment"
    }

    private lateinit var binding: FragmentShareLogsBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentShareLogsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.backButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        refreshLatestLogInfo()

        binding.latestLogRow.setOnClickListener { openLogViewer() }
        binding.viewLogRow.setOnClickListener { openLogViewer() }
        binding.shareLogRow.setOnClickListener { ZHTools.shareLogs(requireActivity()) }
        binding.uploadMclogsRow.setOnClickListener { uploadToMclogs() }
        binding.copyLogRow.setOnClickListener { copyLogToClipboard() }
        binding.clearLogRow.setOnClickListener { confirmClearLogs() }
    }

    /**
     * The log that actually matters right now. See [LatestLogResolver] for the priority
     * order and why a plain "newest mtime" pick was actively misleading.
     */
    private fun latestLogFile(): File? = LatestLogResolver.resolveLatestLogFile()

    private fun refreshLatestLogInfo() {
        val file = latestLogFile()
        if (file == null) {
            binding.latestLogName.text = getString(R.string.share_logs_none_found)
            binding.latestLogSize.text = ""
            return
        }
        val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        binding.latestLogName.text = dateText
        binding.latestLogSize.text = getString(R.string.file_size_label, FileTools.formatFileSize(file.length()))
    }

    private fun openLogViewer() {
        val file = latestLogFile()
        if (file == null) {
            Toast.makeText(requireContext(), R.string.share_logs_none_found, Toast.LENGTH_SHORT).show()
            return
        }
        ZHTools.swapFragmentWithAnim(this, LogViewerFragment::class.java, LogViewerFragment.TAG, LogViewerFragment.createArgs(file))
    }

    private fun uploadToMclogs() {
        val file = latestLogFile()
        if (file == null) {
            Toast.makeText(requireContext(), R.string.share_logs_none_found, Toast.LENGTH_SHORT).show()
            return
        }
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
            com.endiq.turtlelauncher.feature.log.Logging.e("ShareLogsFragment", "mclo.gs upload task failed", e)
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

    private fun copyLogToClipboard() {
        val file = latestLogFile()
        if (file == null) {
            Toast.makeText(requireContext(), R.string.share_logs_none_found, Toast.LENGTH_SHORT).show()
            return
        }
        val content = runCatching { CrashAnalyzer.tailOf(file, 128 * 1024) }.getOrDefault("")
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, content))
        Toast.makeText(requireContext(), R.string.share_logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearLogs() {
        TipDialog.Builder(requireContext())
            .setTitle(R.string.share_logs_clear_confirm_title)
            .setMessage(R.string.share_logs_clear_confirm_message)
            .setWarning()
            .setConfirm(R.string.generic_delete)
            .setConfirmClickListener {
                val logDir = File(PathManager.DIR_LAUNCHER_LOG)
                logDir.listFiles { f -> f.isFile }?.forEach { it.delete() }
                refreshLatestLogInfo()
                Toast.makeText(requireContext(), R.string.share_logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .buildDialog()
            .show()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.exit()))
    }
}
