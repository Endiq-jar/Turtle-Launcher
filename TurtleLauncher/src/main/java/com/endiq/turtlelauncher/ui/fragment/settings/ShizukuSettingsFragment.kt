package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentShizukuBinding
import com.endiq.turtlelauncher.feature.shizuku.ShizukuActions
import com.endiq.turtlelauncher.feature.shizuku.ShizukuManager
import com.endiq.turtlelauncher.feature.shizuku.ShizukuState
import com.endiq.turtlelauncher.feature.shizuku.ShizukuStatus
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools


class ShizukuSettingsFragment : FragmentWithAnim(R.layout.settings_fragment_shizuku) {
    companion object {
        const val TAG: String = "ShizukuSettingsFragment"
        private const val SHIZUKU_DOWNLOAD_URI = "https://shizuku.rikka.app/download/"
    }

    private var _binding: SettingsFragmentShizukuBinding? = null
    private val binding get() = _binding!!

    private var unregisterStatusListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SettingsFragmentShizukuBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()

        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        SwitchSettingsWrapper(
            context,
            AllSettings.shizukuEnabled,
            binding.shizukuEnabledLayout,
            binding.shizukuEnabled
        )
        SwitchSettingsWrapper(
            context,
            AllSettings.shizukuAutoPerformance,
            binding.shizukuAutoPerfLayout,
            binding.shizukuAutoPerf
        )

        binding.shizukuActionButton.setOnClickListener { onActionButtonClicked() }
        binding.shizukuGrantLayout.setOnClickListener { runGrantPermissions() }
        binding.shizukuPerfLayout.setOnClickListener { runPerformanceTweaks() }
        binding.shizukuLogcatLayout.setOnClickListener { runLogcatDump() }
    }

    override fun onStart() {
        super.onStart()
        unregisterStatusListener = ShizukuManager.addListener { applyStatus(it) }
    }

    override fun onStop() {
        unregisterStatusListener?.invoke()
        unregisterStatusListener = null
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.exit()))
    }

    private fun onActionButtonClicked() {
        when (ShizukuManager.status.state) {
            ShizukuState.NOT_INSTALLED,
            ShizukuState.NOT_RUNNING -> openDownloadPage()
            ShizukuState.PERMISSION_DENIED -> ShizukuManager.requestPermission()
            ShizukuState.READY -> runPerformanceTweaks()
        }
    }

    private fun openDownloadPage() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URI)))
        }
    }

    private fun applyStatus(status: ShizukuStatus) {
        val view = _binding ?: return
        val context = view.root.context

        val labelRes = when (status.state) {
            ShizukuState.NOT_INSTALLED -> R.string.shizuku_status_not_installed
            ShizukuState.NOT_RUNNING -> R.string.shizuku_status_not_running
            ShizukuState.PERMISSION_DENIED -> R.string.shizuku_status_permission_denied
            ShizukuState.READY -> if (status.isRoot) R.string.shizuku_status_ready_root
            else R.string.shizuku_status_ready_adb
        }
        view.shizukuStatusValue.setText(labelRes)
        view.shizukuStatusValue.setTextColor(
            ContextCompat.getColor(
                context,
                if (status.state == ShizukuState.READY) R.color.accent_primary
                else R.color.turtle_text_secondary
            )
        )

        view.shizukuStatusDetail.text = if (status.version > 0) {
            context.getString(R.string.shizuku_status_version, status.version) + "\n" + status.detail
        } else {
            status.detail
        }

        val buttonRes = when (status.state) {
            ShizukuState.NOT_INSTALLED -> R.string.shizuku_action_install
            ShizukuState.NOT_RUNNING -> R.string.shizuku_action_open_shizuku
            ShizukuState.PERMISSION_DENIED -> R.string.shizuku_action_grant
            ShizukuState.READY -> R.string.shizuku_action_apply_now
        }
        view.shizukuActionButton.setText(buttonRes)

        val enabled = status.state == ShizukuState.READY && AllSettings.shizukuEnabled.getValue()
        view.shizukuGrantLayout.isEnabled = enabled
        view.shizukuPerfLayout.isEnabled = enabled
        view.shizukuLogcatLayout.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        view.shizukuGrantLayout.alpha = alpha
        view.shizukuPerfLayout.alpha = alpha
        view.shizukuLogcatLayout.alpha = alpha
    }

    private fun runGrantPermissions() {
        val ctx = context ?: return
        if (!guardReady()) return
        markBusy(binding.shizukuGrantValue, ctx)
        val title = ctx.getString(R.string.shizuku_result_permissions)
        val resultTitle = ctx.getString(R.string.shizuku_result_title)
        TaskExecutors.getDefault().execute {
            val outcomes = ShizukuActions.grantPermissions()
            val report = formatOutcomes(ctx, title, outcomes)
            val count = ctx.getString(R.string.shizuku_result_count, outcomes.count { it.ok }, outcomes.size)
            TaskExecutors.runInUIThread {
                val view = _binding ?: return@runInUIThread
                view.shizukuGrantValue.text = count
                showResult(resultTitle, report)
            }
        }
    }

    private fun runPerformanceTweaks() {
        val ctx = context ?: return
        if (!guardReady()) return
        markBusy(binding.shizukuPerfValue, ctx)
        val title = ctx.getString(R.string.shizuku_result_performance)
        val resultTitle = ctx.getString(R.string.shizuku_result_title)
        TaskExecutors.getDefault().execute {
            val outcomes = ShizukuActions.applyPerformanceTweaks()
            val report = formatOutcomes(ctx, title, outcomes)
            val count = ctx.getString(R.string.shizuku_result_count, outcomes.count { it.ok }, outcomes.size)
            TaskExecutors.runInUIThread {
                val view = _binding ?: return@runInUIThread
                view.shizukuPerfValue.text = count
                showResult(resultTitle, report)
            }
        }
    }

    private fun runLogcatDump() {
        val ctx = context ?: return
        if (!guardReady()) return
        markBusy(binding.shizukuLogcatValue, ctx)
        TaskExecutors.getDefault().execute {
            val log = ShizukuActions.dumpLogcat()
            val count = ctx.getString(R.string.shizuku_result_characters, log.length)
            val title = ctx.getString(R.string.shizuku_result_logcat_title)
            val body = if (log.isBlank()) ctx.getString(R.string.shizuku_result_logcat_empty)
            else log.takeLast(16 * 1024)
            TaskExecutors.runInUIThread {
                val view = _binding ?: return@runInUIThread
                view.shizukuLogcatValue.text = count
                showResult(title, body)
            }
        }
    }

    /**
     * Shizuku's shell results are only meaningful when the privilege is actually there, so
     * every action starts by re-checking: the service can be stopped (or its permission
     * revoked) between this screen being built and a row being tapped.
     */
    private fun guardReady(): Boolean {
        if (!AllSettings.shizukuEnabled.getValue()) return false
        if (!ShizukuManager.isReady) {
            ShizukuManager.requestPermission()
            return false
        }
        return true
    }

    private fun markBusy(target: TextView, context: Context) {
        target.text = context.getString(R.string.shizuku_result_working)
    }

    private fun formatOutcomes(
        context: Context,
        title: String,
        outcomes: List<ShizukuActions.ActionOutcome>
    ): String = buildString {
        outcomes.forEach { outcome ->
            append(if (outcome.ok) "✓ " else "✗ ")
            append(title)
            append(" · ")
            append(outcome.label)
            if (outcome.detail.isNotBlank()) append(": ").append(outcome.detail)
            appendLine()
        }
    }

    private fun showResult(title: String, text: String) {
        val view = _binding ?: return
        view.shizukuResultCard.visibility = View.VISIBLE
        view.shizukuResultText.text = "$title\n\n$text"
    }
}
