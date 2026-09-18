package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentExperimentalBinding
import com.endiq.turtlelauncher.feature.pluginupdate.PluginUpdateManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper


class ExperimentalSettingsFragment :
    AbstractSettingsFragment(R.layout.settings_fragment_experimental, SettingCategory.EXPERIMENTAL) {
    companion object {
        const val TAG: String = "ExperimentalSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentExperimentalBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentExperimentalBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { com.endiq.turtlelauncher.utils.ZHTools.onBackPressed(requireActivity()) }

        SwitchSettingsWrapper(
            context,
            AllSettings.dumpShaders,
            binding.dumpShadersLayout,
            binding.dumpShaders
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.bigCoreAffinity,
            binding.bigCoreAffinityLayout,
            binding.bigCoreAffinity
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.fastBoot,
            binding.fastBootLayout,
            binding.fastBoot
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.modConflictDetection,
            binding.modConflictDetectionLayout,
            binding.modConflictDetection
        )

        ListSettingsWrapper(
            context, AllSettings.lwjglCompatMode,
            binding.lwjglCompatModeLayout, binding.lwjglCompatModeTitle, binding.lwjglCompatModeValue,
            R.array.lwjgl_compat_mode_names, R.array.lwjgl_compat_mode_values
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.backgroundServiceOptimization,
            binding.backgroundServiceOptimizationLayout,
            binding.backgroundServiceOptimization
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.tcVibrateDuration,
            binding.tcVibrateDurationLayout,
            binding.tcVibrateDurationTitle,
            binding.tcVibrateDurationSummary,
            binding.tcVibrateDurationValue,
            binding.tcVibrateDuration,
            "ms"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.autoCheckPluginUpdates,
            binding.autoCheckPluginUpdatesLayout,
            binding.autoCheckPluginUpdates
        )

        // ── Advanced Tools ──────────────────────────────────────────────────
        SwitchSettingsWrapper(context, AllSettings.offlineModeFallback, binding.offlineModeFallbackLayout, binding.offlineModeFallback)
        SwitchSettingsWrapper(context, AllSettings.anrDetectorEnabled, binding.anrDetectorEnabledLayout, binding.anrDetectorEnabled)

        BaseSettingsWrapper(context, binding.exportSettingsLayout) {
            val destFile = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "TurtleLauncher_settings_backup.json"
            )
            val ok = com.endiq.turtlelauncher.feature.turtle.SettingsBackupManager.exportToFile(destFile)
            Toast.makeText(
                context,
                getString(if (ok) R.string.export_settings_success else R.string.export_settings_failure),
                Toast.LENGTH_LONG
            ).show()
        }

        BaseSettingsWrapper(context, binding.importSettingsLayout) {
            val srcFile = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "TurtleLauncher_settings_backup.json"
            )
            val ok = com.endiq.turtlelauncher.feature.turtle.SettingsBackupManager.importFromFile(srcFile)
            Toast.makeText(
                context,
                getString(if (ok) R.string.import_settings_success else R.string.import_settings_failure),
                Toast.LENGTH_LONG
            ).show()
            if (ok) {
                // Settings were replaced under this fragment's feet — reload it so every
                // switch/seekbar reflects the freshly imported values instead of stale UI state.
                parentFragmentManager.beginTransaction().detach(this@ExperimentalSettingsFragment).attach(this@ExperimentalSettingsFragment).commit()
            }
        }

        BaseSettingsWrapper(context, binding.crashHistoryLayout) {
            val history = com.endiq.turtlelauncher.feature.log.CrashAnalyzer.getCrashHistory()
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val message = if (history.isEmpty()) {
                getString(R.string.crash_diagnostics_dialog_empty)
            } else {
                history.joinToString("\n\n") { entry ->
                    "${formatter.format(java.util.Date(entry.timestampMs))} (exit ${entry.exitCode})\n${entry.summary}"
                }
            }
            com.endiq.turtlelauncher.ui.dialog.TipDialog.Builder(context)
                .setTitle(R.string.setting_crash_history_title)
                .setMessage(message)
                .setCenterMessage(false)
                .setSelectable(true)
                .setShowCancel(history.isNotEmpty())
                .setCancel(R.string.generic_clear)
                .setCancelClickListener { com.endiq.turtlelauncher.feature.log.CrashAnalyzer.clearCrashHistory() }
                .setConfirm(R.string.generic_ok)
                .showDialog()
        }

        BaseSettingsWrapper(context, binding.crashRuleEditorLayout) {
            showCrashRuleEditor(context)
        }

        SwitchSettingsWrapper(
            context,
            com.endiq.turtlelauncher.setting.AllSettings.aiCrashHelpEnabled,
            binding.aiCrashHelpLayout,
            binding.aiCrashHelp
        )

        BaseSettingsWrapper(context, binding.aiCrashHelpApiKeyLayout) {
            promptSetAiCrashHelpApiKey(context)
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.aiSkinFilterEnabled,
            binding.aiSkinFilterLayout,
            binding.aiSkinFilter
        )

        BaseSettingsWrapper(context, binding.dependencyGraphLayout) {
            val version = com.endiq.turtlelauncher.feature.version.VersionsManager.getCurrentVersion()
            if (version == null) {
                Toast.makeText(context, R.string.dependency_graph_no_version, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.setting_dependency_graph_title, Toast.LENGTH_SHORT).show()
                Thread {
                    val graphText = com.endiq.turtlelauncher.feature.turtle.DependencyGraphExporter.buildTextTree(version)
                    com.endiq.turtlelauncher.task.TaskExecutors.runInUIThread {
                        com.endiq.turtlelauncher.ui.dialog.TipDialog.Builder(context)
                            .setTitle(R.string.setting_dependency_graph_title)
                            .setMessage(graphText)
                            .setCenterMessage(false)
                            .setSelectable(true)
                            .setShowCancel(false)
                            .setConfirm(R.string.generic_ok)
                            .showDialog()
                    }
                }.start()
            }
        }

        BaseSettingsWrapper(context, binding.configEditorLayout) {
            val version = com.endiq.turtlelauncher.feature.version.VersionsManager.getCurrentVersion()
            if (version == null) {
                Toast.makeText(context, R.string.dependency_graph_no_version, Toast.LENGTH_SHORT).show()
            } else {
                val intent = android.content.Intent(context, com.endiq.turtlelauncher.ui.activity.ConfigEditorActivity::class.java)
                intent.putExtra(com.endiq.turtlelauncher.ui.activity.ConfigEditorActivity.EXTRA_VERSION_NAME, version.getVersionName())
                startActivity(intent)
            }
        }

        BaseSettingsWrapper(
            context,
            binding.checkPluginUpdatesLayout
        ) {
            Toast.makeText(context, getString(R.string.setting_plugin_update_checking), Toast.LENGTH_SHORT).show()
            PluginUpdateManager.checkForUpdates(context, force = true) { updates, error ->
                when {
                    error != null -> Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    updates.isEmpty() -> Toast.makeText(context, getString(R.string.setting_plugin_update_none), Toast.LENGTH_SHORT).show()
                    else -> {
                        // Install everything that changed; each plugin is independent so a
                        // partial failure on one shouldn't block the others.
                        updates.forEach { asset ->
                            PluginUpdateManager.downloadAndInstall(context, asset) { success, message ->
                                Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

        BaseSettingsWrapper(
            context,
            binding.crashDiagnosticsLayout
        ) {
            val snapshot = com.endiq.turtlelauncher.feature.log.CrashAnalyzer.getTelemetrySnapshot(context)
            val message = if (snapshot.isEmpty()) {
                getString(R.string.crash_diagnostics_dialog_empty)
            } else {
                snapshot.joinToString("\n") { (title, count) -> "$title: $count" }
            }
            com.endiq.turtlelauncher.ui.dialog.TipDialog.Builder(context)
                .setTitle(R.string.crash_diagnostics_dialog_title)
                .setMessage(message)
                .setCenterMessage(false)
                .setSelectable(true)
                .setShowCancel(false)
                .setConfirm(R.string.generic_ok)
                .showDialog()
        }
    }

    private fun promptSetAiCrashHelpApiKey(context: android.content.Context) {
        val current = runCatching { com.endiq.turtlelauncher.setting.AllSettings.aiApiKey.getValue() }.getOrDefault("")
        com.endiq.turtlelauncher.ui.dialog.EditTextDialog.Builder(context)
            .setTitle(R.string.setting_ai_crash_help_key_title)
            .setHintText(R.string.setting_ai_crash_help_key_desc)
            .setEditText(current)
            .setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .setConfirmListener { editText, _ ->
                com.endiq.turtlelauncher.setting.AllSettings.aiApiKey.put(editText.text.toString().trim()).save()
                Toast.makeText(context, R.string.generic_ok, Toast.LENGTH_SHORT).show()
                true
            }
            .showDialog()
    }

    private fun showCrashRuleEditor(context: android.content.Context) {
        val rules = com.endiq.turtlelauncher.feature.log.CrashAnalyzer.getCustomRules()
        val message = if (rules.isEmpty()) {
            getString(R.string.crash_rule_editor_empty)
        } else {
            rules.mapIndexed { i, r -> "${i + 1}. ${r.pattern} → ${r.tip}" }.joinToString("\n")
        }
        com.endiq.turtlelauncher.ui.dialog.TipDialog.Builder(context)
            .setTitle(R.string.setting_crash_rule_editor_title)
            .setMessage(message)
            .setCenterMessage(false)
            .setSelectable(true)
            .setShowCancel(true)
            .setCancel(R.string.generic_add)
            .setCancelClickListener { promptAddCrashRule(context) }
            .setConfirm(R.string.generic_ok)
            .showDialog()
    }

    private fun promptAddCrashRule(context: android.content.Context) {
        com.endiq.turtlelauncher.ui.dialog.EditTextDialog.Builder(context)
            .setTitle(R.string.setting_crash_rule_editor_title)
            .setHintText(R.string.crash_rule_editor_pattern_hint)
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                promptAddCrashRuleTip(context, editText.text.toString())
                true
            }
            .showDialog()
    }

    private fun promptAddCrashRuleTip(context: android.content.Context, pattern: String) {
        com.endiq.turtlelauncher.ui.dialog.EditTextDialog.Builder(context)
            .setTitle(R.string.setting_crash_rule_editor_title)
            .setHintText(R.string.crash_rule_editor_tip_hint)
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                com.endiq.turtlelauncher.feature.log.CrashAnalyzer.addCustomRule(pattern, editText.text.toString())
                Toast.makeText(context, R.string.generic_ok, Toast.LENGTH_SHORT).show()
                showCrashRuleEditor(context)
                true
            }
            .showDialog()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }
}
