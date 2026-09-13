package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentLogViewerBinding
import com.endiq.turtlelauncher.feature.log.CrashAnalyzer
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File

/**
 * Built-in log viewer: shows a launcher log file with a live text search (highlights matches)
 * and an "Errors only" filter that narrows the view down to lines carrying
 * [com.endiq.turtlelauncher.feature.log.Logging]'s own `(ERROR)` tag - the same tag every
 * `Logging.e(...)` call writes, so this needs no separate error-detection heuristic. Opened
 * from ShareLogsFragment's "View Log" row instead of the old single-shot TipDialog preview.
 */
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
        val file = path?.let { File(it) }
            // TurtleLauncher: check ALL log sources, not just the launcher log directory.
            // This ensures the viewer shows the most recent log regardless of whether it
            // came from the launcher's rolling logger, the game's stdout capture, or
            // Minecraft's own Log4j2 output — including logs from crashed sessions.
            ?: listOfNotNull(
                File(PathManager.DIR_LAUNCHER_LOG).takeIf { it.isDirectory }
                    ?.listFiles { f -> f.isFile }
                    ?.maxByOrNull { it.lastModified() },
                File(PathManager.DIR_GAME_HOME, "latestlog.txt").takeIf { it.isFile && it.length() > 0 },
                VersionsManager.getCurrentVersion()?.let { v ->
                    File(v.getGameDir(), "logs/latest.log").takeIf { it.isFile && it.length() > 0 }
                }
            ).maxByOrNull { it.lastModified() }

        if (file == null || !file.isFile) {
            binding.logViewerTitle.text = getString(R.string.log_viewer_title)
            binding.logMatchCount.text = getString(R.string.share_logs_none_found)
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

        applyFilter()
    }

    private fun applyFilter() {
        val query = binding.logSearchEdit.text?.toString()?.trim().orEmpty()
        val errorsOnly = binding.logErrorsOnlyCheckbox.isChecked

        val filtered = allLines.filter { line ->
            (!errorsOnly || line.contains("(ERROR)")) &&
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

        val errorColor = ContextCompat.getColor(requireContext(), R.color.turtle_error)
        val warnColor = ContextCompat.getColor(requireContext(), R.color.turtle_warning)
        val highlightColor = ContextCompat.getColor(requireContext(), R.color.accent_primary)

        val builder = SpannableStringBuilder()
        filtered.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val end = builder.length

            when {
                line.contains("(ERROR)") -> builder.setSpan(ForegroundColorSpan(errorColor), start, end, 0)
                line.contains("(WARN)") -> builder.setSpan(ForegroundColorSpan(warnColor), start, end, 0)
            }

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
