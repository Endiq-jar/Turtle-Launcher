package com.endiq.turtlelauncher.feature.mod

import android.content.Context
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.parser.ModInfo
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import java.io.File


/**
 * Runs TurtleLauncher's automatic mod maintenance (dependency installer + update
 * checker) once per launch, right after mods have been parsed and before the
 * existing [com.endiq.turtlelauncher.feature.mod.parser.ModChecker] step.
 *
 * Both features are best-effort and network-bound, so all work happens on a
 * background task; [onComplete] is invoked back on whatever the caller needs
 * (always called exactly once, never throws). Any dialogs this shows are purely
 * informational/optional and do NOT block [onComplete] — launch is never held up
 * waiting for the player to tap a button.
 */
object ModAutoMaintenance {

    // TurtleLauncher: minimum time between real (network-bound) dependency/update checks for
    // the same version. Root cause of "launch takes a while with mods": dependencyEnabled and
    // updateCheckEnabled both make real Modrinth API calls (one lookup per mod that needs a
    // compatible-version check, sequentially) on every single launch by default - for a 30-40
    // mod pack that's 30-40 network round-trips gating the game actually starting, every time,
    // even though this runs off the UI thread (never freezes the launcher, but the player is
    // still standing there watching a progress bar). Fast Boot already exists to skip this
    // entirely; this is the middle ground for everyone else - skip the network calls on a
    // launch that happens shortly after one that already checked, reuse-nothing-stale since we
    // don't persist the actual results, just skip re-asking Modrinth so soon.
    private val MIN_RECHECK_INTERVAL_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(6)

    private fun maintenanceMarkerFile(version: Version): File =
        File(version.getGameDir(), "mods/.turtle_maintenance_check")

    private fun recentlyChecked(version: Version): Boolean {
        val marker = maintenanceMarkerFile(version)
        if (!marker.isFile) return false
        val lastCheck = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)
        return System.currentTimeMillis() - lastCheck < MIN_RECHECK_INTERVAL_MS
    }

    private fun markChecked(version: Version) {
        runCatching {
            val marker = maintenanceMarkerFile(version)
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString())
        }
    }

    @JvmStatic
    fun runForVersion(
        context: Context,
        version: Version,
        modInfoList: List<ModInfo>,
        onComplete: Runnable
    ) {
        val skipNetworkChecks = recentlyChecked(version)
        val dependencyEnabled = runCatching { AllSettings.autoDependencyInstall.getValue() }.getOrDefault(true) &&
                !runCatching { AllSettings.fastBoot.getValue() }.getOrDefault(false) && !skipNetworkChecks
        val updateCheckEnabled = runCatching { AllSettings.autoModUpdateCheck.getValue() }.getOrDefault(true) &&
                !runCatching { AllSettings.fastBoot.getValue() }.getOrDefault(false) && !skipNetworkChecks
        // Conflict detection is cheap (static jar/bytecode scan, no network) so it still runs
        // every launch regardless - it's exactly the kind of check that helps when something's
        // wrong, and doesn't contribute to the network-bound delay this is working around.
        val conflictCheckEnabled = runCatching { AllSettings.modConflictDetection.getValue() }.getOrDefault(true)

        if (!dependencyEnabled && !updateCheckEnabled && !conflictCheckEnabled) {
            onComplete.run()
            return
        }
        if (dependencyEnabled || updateCheckEnabled) markChecked(version)

        Task.runTask {
            var dependencyResult: ModDependencyResolver.ResolveResult? = null
            var updates: List<ModUpdateChecker.UpdateInfo> = emptyList()
            var conflicts: List<ModConflictDetector.Conflict> = emptyList()

            runCatching {
                val versionInfo = version.getVersionInfo()
                val mcVersion = versionInfo?.minecraftVersion
                val loader = versionInfo?.loaderInfo?.firstNotNullOfOrNull { ModLoaderUtils.getModLoader(it.name) }
                val modsFolder = File(version.getGameDir(), "mods")

                if (mcVersion != null && loader != null && modsFolder.isDirectory) {
                    if (dependencyEnabled) {
                        dependencyResult = ModDependencyResolver.resolveMissingDependencies(
                            modsFolder, modInfoList, mcVersion, loader
                        )
                    }
                    if (updateCheckEnabled) {
                        updates = ModUpdateChecker.checkForUpdates(modInfoList, mcVersion, loader)
                    }
                }
                if (conflictCheckEnabled) {
                    val modsFolderForConflicts = File(version.getGameDir(), "mods")
                    if (modsFolderForConflicts.isDirectory) {
                        conflicts = ModConflictDetector.detectConflicts(modsFolderForConflicts)
                    }
                }
            }.onFailure { e -> Logging.e("ModAutoMaintenance", "Mod auto-maintenance failed", e) }

            Triple(dependencyResult, updates, conflicts)
        }.ended { result ->
            val safeResult: Triple<ModDependencyResolver.ResolveResult?, List<ModUpdateChecker.UpdateInfo>, List<ModConflictDetector.Conflict>> =
                result ?: Triple(null, emptyList(), emptyList())
            val dependencyResult = safeResult.first
            val updates = safeResult.second
            val conflicts = safeResult.third

            if ((dependencyResult != null && !dependencyResult.isEmpty) || updates.isNotEmpty() || conflicts.isNotEmpty()) {
                TaskExecutors.getAndroidUI().execute {
                    if (dependencyResult != null && !dependencyResult.isEmpty) {
                        showDependencyResultDialog(context, dependencyResult)
                    }
                    if (updates.isNotEmpty()) {
                        showUpdateAvailableDialog(context, updates)
                    }
                    if (conflicts.isNotEmpty()) {
                        showConflictWarningDialog(context, conflicts)
                    }
                }
            }

            // Dialogs above are fire-and-forget/informational — don't block the launch on them.
            // Keep onComplete on this same (background) executor, matching the threading the
            // pre-existing ModChecker step already ran on.
            onComplete.run()
        }.onThrowable {
            onComplete.run()
        }.execute()
    }

    private fun showDependencyResultDialog(context: Context, result: ModDependencyResolver.ResolveResult) {
        val message = buildString {
            if (result.installed.isNotEmpty()) {
                append(context.getString(R.string.dependency_installer_installed_header, result.installed.size))
                result.installed.forEach { append("\n • ").append(it) }
            }
            if (result.failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(context.getString(R.string.dependency_installer_failed_header))
                result.failed.forEach { append("\n • ").append(it) }
            }
        }
        if (message.isBlank()) return

        TipDialog.Builder(context)
            .setTitle(R.string.dependency_installer_dialog_title)
            .setMessage(message)
            .setCenterMessage(false)
            .setSelectable(true)
            .setShowCancel(false)
            .setConfirm(R.string.generic_ok)
            .showDialog()
    }

    private fun showUpdateAvailableDialog(context: Context, updates: List<ModUpdateChecker.UpdateInfo>) {
        val message = android.text.SpannableStringBuilder().apply {
            append(context.getString(R.string.mod_update_dialog_header, updates.size))
            updates.forEach { update ->
                append("\n • ")
                // Highlight each "modName: current → new" entry in green so available
                // updates stand out clearly against the rest of the dialog text.
                val entryStart = length
                append(update.modName).append(": ")
                    .append(update.currentVersion).append(" → ").append(update.newVersionNumber)
                setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#9CCF93")), // Status: Success
                    entryStart, length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        TipDialog.Builder(context)
            .setTitle(R.string.mod_update_dialog_title)
            .setMessage(message)
            .setCenterMessage(false)
            .setSelectable(true)
            .setConfirm(R.string.mod_update_dialog_update_now)
            .setCancel(R.string.mod_update_dialog_later)
            .setConfirmClickListener { _ -> applyUpdatesInBackground(context, updates) }
            .showDialog()
    }

    private fun showConflictWarningDialog(context: Context, conflicts: List<ModConflictDetector.Conflict>) {
        val message = android.text.SpannableStringBuilder().apply {
            append(context.getString(R.string.mod_conflict_dialog_header, conflicts.size))
            conflicts.forEach { conflict ->
                append("\n • ")
                val entryStart = length
                append(conflict.targetClass)
                setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF8A8A")), // Status: Error
                    entryStart, length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append(" — ").append(conflict.modNames.joinToString(", "))
            }
        }

        TipDialog.Builder(context)
            .setTitle(R.string.mod_conflict_dialog_title)
            .setMessage(message)
            .setCenterMessage(false)
            .setSelectable(true)
            .setShowCancel(false)
            .setConfirm(R.string.generic_ok)
            .showDialog()
    }

    private fun applyUpdatesInBackground(context: Context, updates: List<ModUpdateChecker.UpdateInfo>) {
        Task.runTask {
            ModUpdateChecker.applyUpdates(updates)
        }.ended(TaskExecutors.getAndroidUI()) { result ->
            val (success, failed) = result ?: Pair(0, updates.size)
            TipDialog.Builder(context)
                .setTitle(R.string.mod_update_dialog_title)
                .setMessage(context.getString(R.string.mod_update_result_message, success, failed))
                .setShowCancel(false)
                .setConfirm(R.string.generic_ok)
                .showDialog()
        }.onThrowable {
            Logging.e("ModAutoMaintenance", "Failed to apply mod updates")
        }.execute()
    }
}
