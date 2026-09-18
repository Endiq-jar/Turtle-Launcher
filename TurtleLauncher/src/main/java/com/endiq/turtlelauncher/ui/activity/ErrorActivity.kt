package com.endiq.turtlelauncher.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.endiq.turtlelauncher.InfoCenter
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.ActivityErrorBinding
import com.endiq.turtlelauncher.feature.log.CrashAnalyzer
import com.endiq.turtlelauncher.feature.log.GameLogcat
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.log.SelfHealingManager
import com.endiq.turtlelauncher.feature.shizuku.ShizukuActions
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File

class ErrorActivity : BaseActivity() {
    private lateinit var binding: ActivityErrorBinding
    private var advancedLogContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val extras = intent.extras
        extras ?: run {
            finish()
            return
        }

        binding.errorConfirm.setOnClickListener { dismissOrReturnToLauncher() }
        binding.errorRestart.setOnClickListener {
            startActivity(Intent(this@ErrorActivity, SplashActivity::class.java))
        }
        binding.shareLog.setOnClickListener { ZHTools.shareLogs(this) }

        binding.toggleAdvancedLog.setOnClickListener {
            val showing = binding.advancedLogSection.visibility == View.VISIBLE
            binding.advancedLogSection.visibility = if (showing) View.GONE else View.VISIBLE
            binding.toggleAdvancedLog.setText(
                if (showing) R.string.crash_show_advanced_log else R.string.crash_hide_advanced_log
            )
        }
        binding.copyLog.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("crash_log", advancedLogContent))
            Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
        }

        if (extras.getBoolean(BUNDLE_IS_LAUNCHER_CRASH, false)) {
            showLauncherCrash(extras)
            return
        }
        if (extras.getBoolean(BUNDLE_IS_GAME_CRASH, false)) {
            // Unless the app crashed, this page may not be captured in screenshots.
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showGameCrash(extras)
            return
        }
        if (extras.getBoolean(BUNDLE_EASTER_EGG, false)) {
            showEasterEgg()
            return
        }

        finish()
    }

    private fun dismissOrReturnToLauncher() {
        if (!isTaskRoot) {
            finish()
            return
        }
        runCatching {
            startActivity(
                Intent(this, SplashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            Logging.e("ErrorActivity", "Could not return to the launcher from the crash screen", it)
        }
        finish()
    }

    /** Populates the "How to fix this" section, or hides it if there's nothing useful to say. */
    private fun showFixTips(diagnosisText: String?) {
        binding.apply {
            if (diagnosisText.isNullOrBlank()) {
                fixTitle.visibility = View.VISIBLE
                fixText.visibility = View.VISIBLE
                fixText.text = getString(R.string.crash_no_diagnosis)
            } else {
                fixTitle.visibility = View.VISIBLE
                fixText.visibility = View.VISIBLE
                fixText.text = diagnosisText
            }
        }
    }

    /** Sets the raw text shown behind the "Advanced Log" toggle. */
    private fun setAdvancedLog(rawText: String) {
        advancedLogContent = rawText.ifBlank { "<no log available>" }
        binding.advancedLogText.text = advancedLogContent
    }

    private fun showDiagnosisActions() {
        val diagnoses = runCatching { CrashAnalyzer.getLastDiagnoses() }.getOrDefault(emptyList())
        val gameVersion = runCatching { CrashAnalyzer.getLastGameVersion() }.getOrNull()
        val topDiagnosis = diagnoses.firstOrNull()
        val repairAction = diagnoses.firstOrNull { it.repairActions.isNotEmpty() }?.repairActions?.firstOrNull()

        binding.apply {
            if (repairAction != null) {
                crashRepairButton.visibility = View.VISIBLE
                crashRepairButton.text = repairAction.label
                crashRepairButton.setOnClickListener { runRepair(repairAction, gameVersion) }
            } else {
                crashRepairButton.visibility = View.GONE
            }

            if (topDiagnosis != null) {
                crashSearchOnlineButton.visibility = View.VISIBLE
                crashSearchOnlineButton.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CrashAnalyzer.onlineSearchUrl(topDiagnosis))))
                    }
                }

                crashExportButton.visibility = View.VISIBLE
                crashExportButton.setOnClickListener { exportDiagnostics(diagnoses, gameVersion) }
            } else {
                crashSearchOnlineButton.visibility = View.GONE
                crashExportButton.visibility = View.GONE
            }
        }

        runSelfHeal(diagnoses, gameVersion)
    }

    private fun runSelfHeal(diagnoses: List<CrashAnalyzer.Diagnosis>, gameVersion: com.endiq.turtlelauncher.feature.version.Version?) {
        if (diagnoses.none { it.repairActions.isNotEmpty() }) {
            binding.selfHealStatus.visibility = View.GONE
            return
        }

        binding.selfHealStatus.visibility = View.VISIBLE
        binding.selfHealStatus.text = getString(R.string.self_heal_repairing)

        TaskExecutors.getDefault().execute {
            val outcome = runCatching { SelfHealingManager.autoHeal(diagnoses, gameVersion) }.getOrNull()
            TaskExecutors.runInUIThread {
                if (outcome == null || !outcome.triggered) {
                    binding.selfHealStatus.visibility = View.GONE
                } else {
                    binding.selfHealStatus.text = outcome.summary
                    // Already repaired automatically — the manual button would just redo the
                    // same work, so hide it rather than leave a stale/confusing duplicate action.
                    binding.crashRepairButton.visibility = View.GONE
                }
            }
        }
    }

    /** Runs [action] off the UI thread, then reports the result and refreshes the fix text. */
    private fun runRepair(action: CrashAnalyzer.RepairAction, gameVersion: com.endiq.turtlelauncher.feature.version.Version?) {
        binding.crashRepairButton.isEnabled = false
        TaskExecutors.getDefault().execute {
            val result = runCatching { CrashAnalyzer.executeRepair(action, gameVersion) }
                .getOrElse { e -> CrashAnalyzer.RepairResult(false, e.message ?: "Repair failed") }
            TaskExecutors.runInUIThread {
                binding.crashRepairButton.isEnabled = true
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Writes a diagnostics export file off the UI thread, then offers to share it. */
    private fun exportDiagnostics(diagnoses: List<CrashAnalyzer.Diagnosis>, gameVersion: com.endiq.turtlelauncher.feature.version.Version?) {
        binding.crashExportButton.isEnabled = false
        TaskExecutors.getDefault().execute {
            val file = runCatching {
                CrashAnalyzer.exportDiagnostics(diagnoses, logText = advancedLogContent, gameVersion = gameVersion)
            }.getOrNull()
            TaskExecutors.runInUIThread {
                binding.crashExportButton.isEnabled = true
                if (file == null) {
                    Toast.makeText(this, R.string.crash_export_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.crash_export_saved, file.name), Toast.LENGTH_LONG).show()
                    FileTools.shareFile(this, file)
                }
            }
        }
    }

    private fun showLauncherCrash(extras: Bundle) {
        val context = this

        val throwable = extras.getSerializable(BUNDLE_THROWABLE) as Throwable?
        val stackTrace = if (throwable != null) Tools.printToString(throwable) else "<null>"
        val strSavePath = extras.getString(BUNDLE_SAVE_PATH)

        binding.apply {
            this.errorTitle.text = InfoCenter.replaceName(context, R.string.error_fatal)
            this.errorText.text = getString(R.string.crash_how_to_fix)

            this.topView.setBackgroundColor(ContextCompat.getColor(context, R.color.background_menu_top_error))
            this.background.setBackgroundColor(ContextCompat.getColor(context, R.color.background_app_error))
        }

        // Prefer the on-disk crash report (it has device/version info too); fall back to the raw stack trace.
        val savedReport = strSavePath?.let { runCatching { File(it).takeIf { f -> f.exists() }?.readText() }.getOrNull() }
        val fullLog = savedReport ?: stackTrace
        setAdvancedLog(fullLog)

        val diagnoses = runCatching { CrashAnalyzer.analyze(stackTrace) }.getOrDefault(emptyList())
        showFixTips(if (diagnoses.isEmpty()) null else CrashAnalyzer.formatForDisplay(diagnoses))
        showDiagnosisActions()
    }

    private fun showGameCrash(extras: Bundle) {
        val code = extras.getInt(BUNDLE_CODE, 0)
        if (code == 0) {
            finish()
            return
        }
        val errorText = if (extras.getBoolean(BUNDLE_IS_SIGNAL)) R.string.game_singnal_message else R.string.game_exit_message
        val diagnosis = extras.getString(BUNDLE_DIAGNOSIS)

        val context = this

        binding.apply {
            this.errorTitle.setText(R.string.generic_wrong_tip)
            this.errorText.apply {
                text = getString(errorText, code)
                textSize = 13f
                setTextIsSelectable(true)
            }
            this.errorTip.visibility = View.VISIBLE
            this.errorNoScreenshot.visibility = View.VISIBLE

            this.topView.setBackgroundColor(ContextCompat.getColor(context, R.color.background_menu_top))
            this.background.setBackgroundColor(ContextCompat.getColor(context, R.color.background_app))
        }

        showFixTips(diagnosis)
        // The diagnosis already summarises the log; Advanced Log still gives access to the raw tail
        // for anyone (or anyone helping them) who needs the unfiltered details.
        applyGameCrashAdvancedLog(diagnosis)
        // Structured diagnoses for this exact crash were already stashed by CrashAnalyzer.analyzeGameExit()
        // (called from JREUtils right before this activity was launched) — see getLastDiagnoses().
        showDiagnosisActions()
    }

    private fun applyGameCrashAdvancedLog(diagnosis: String?) {
        val nativeLog = runCatching {
            CrashAnalyzer.tailOf(File(PathManager.DIR_GAME_HOME, "latestlog.txt"), 64 * 1024)
        }.getOrDefault("")
        if (nativeLog.isNotBlank()) {
            setAdvancedLog(nativeLog)
            return
        }

        val sessionLogcat = GameLogcat.readSessionTail()
        if (sessionLogcat.isNotBlank()) {
            setAdvancedLog(GameLogcat.formatForReport(sessionLogcat))
            return
        }

        setAdvancedLog("")
        TaskExecutors.getDefault().execute {
            val fallback = ShizukuActions.dumpLogcat(4000)
                .takeIf { it.isNotBlank() }
                ?: GameLogcat.dumpNow()
            val text = if (fallback.isNotBlank()) GameLogcat.formatForReport(fallback)
            else diagnosis ?: ""
            TaskExecutors.runInUIThread { setAdvancedLog(text) }
        }
    }

    private fun showEasterEgg() {
        val context = this

        binding.apply {
            this.topView.visibility = View.GONE
            this.scrollView.visibility = View.GONE
            this.shareLog.visibility = View.GONE
            this.errorRestart.visibility = View.GONE
            this.errorConfirm.visibility = View.GONE
            this.centerText.visibility = View.VISIBLE

            this.centerText.text = InfoCenter.replaceName(context, R.string.error_fatal)

            this.topView.setBackgroundColor(ContextCompat.getColor(context, R.color.background_menu_top_error))
            this.background.setBackgroundResource(R.drawable.image_error_background)
        }
    }

    companion object {
        private const val BUNDLE_IS_LAUNCHER_CRASH = "is_launcher_crash"
        private const val BUNDLE_IS_GAME_CRASH = "is_game_crash"
        private const val BUNDLE_IS_SIGNAL = "is_signal"
        private const val BUNDLE_CODE = "code"
        private const val BUNDLE_THROWABLE = "throwable"
        private const val BUNDLE_SAVE_PATH = "save_path"
        private const val BUNDLE_EASTER_EGG = "easter_egg"
        private const val BUNDLE_DIAGNOSIS = "crash_diagnosis"

        @JvmStatic
        fun showLauncherCrash(ctx: Context, savePath: String?, th: Throwable?) {
            val intent = Intent(ctx, ErrorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(BUNDLE_THROWABLE, th)
            intent.putExtra(BUNDLE_SAVE_PATH, savePath)
            intent.putExtra(BUNDLE_IS_LAUNCHER_CRASH, true)
            ctx.startActivity(intent)
        }

        /**
         * @param diagnosis optional pre-formatted [com.endiq.turtlelauncher.feature.log.CrashAnalyzer]
         * output to display alongside the generic exit message. Pass null/blank for none.
         */
        @JvmOverloads
        @JvmStatic
        fun showExitMessage(
            ctx: Context,
            code: Int,
            isSignal: Boolean,
            diagnosis: String? = null
        ) {
            val intent = Intent(ctx, ErrorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(BUNDLE_CODE, code)
            intent.putExtra(BUNDLE_IS_LAUNCHER_CRASH, false)
            intent.putExtra(BUNDLE_IS_SIGNAL, isSignal)
            intent.putExtra(BUNDLE_IS_GAME_CRASH, true)
            if (!diagnosis.isNullOrBlank()) intent.putExtra(BUNDLE_DIAGNOSIS, diagnosis)
            ctx.startActivity(intent)
        }

        @JvmStatic
        fun showEasterEgg(ctx: Context) {
            val intent = Intent(ctx, ErrorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(BUNDLE_EASTER_EGG, true)
            ctx.startActivity(intent)
        }
    }
}
