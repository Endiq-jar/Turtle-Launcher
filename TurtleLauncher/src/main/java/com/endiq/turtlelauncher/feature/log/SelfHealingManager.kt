package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.feature.version.Version

/**
 * TurtleLauncher Self-Healing Launcher (roadmap #9).
 *
 * Turns Crash Analyzer 2.0's existing one-click [CrashAnalyzer.RepairAction] system from
 * "tap to fix" into "fixed automatically" — but only for the diagnoses that actually carry
 * a repair action, i.e. only issues CrashAnalyzer is already concretely certain about (a
 * corrupted file, a broken renderer override, missing assets, a broken Java runtime...).
 * Diagnoses with no repair action (unrecognised causes, mod-conflict warnings, the AI
 * fallback suggestion) are never touched — self-healing only ever acts on things Crash
 * Analyzer already knows how to fix, it never guesses.
 *
 * This is deliberately just an automatic-invocation layer on top of [CrashAnalyzer.executeRepair]
 * — it doesn't reimplement any repair logic itself, so every safety scoping already built into
 * the individual repair actions (corrupted-file delete confined to the game folder, permission
 * fixes confined to specific known folders, config restoration only for zero-byte/unparsable
 * files, etc.) applies here exactly as it does to the manual "Fix it" button. Nothing here can
 * touch worlds, screenshots, accounts, or anything outside what executeRepair itself scopes to.
 */
object SelfHealingManager {

    /** One repair action that was actually run, and what happened when it ran. */
    data class HealStep(val action: CrashAnalyzer.RepairAction, val result: CrashAnalyzer.RepairResult)

    data class HealOutcome(
        /** False if there was nothing to repair — [steps] is always empty in that case. */
        val triggered: Boolean,
        val steps: List<HealStep>,
        /** Plain-language multi-line summary, ready to display as-is. Empty when [triggered] is false. */
        val summary: String
    )

    /**
     * Looks at [diagnoses] for every distinct repair action CrashAnalyzer can actually perform,
     * and runs all of them synchronously via [CrashAnalyzer.executeRepair]. Call this off the
     * main thread — it can block on a network re-download (see [CrashAnalyzer.RepairActionType.VERIFY_GAME_FILES]).
     *
     * [onStatus] is invoked once, right before any repairs start, with the roadmap's own
     * "Issues Found. Repairing automatically..." wording — purely informational, the UI decides
     * how (or whether) to render it. Never invoked if there's nothing to repair.
     */
    @JvmStatic
    @JvmOverloads
    fun autoHeal(
        diagnoses: List<CrashAnalyzer.Diagnosis>,
        gameVersion: Version?,
        onStatus: ((String) -> Unit)? = null
    ): HealOutcome {
        // Dedupe across diagnoses: two different matched rules can both ask for e.g.
        // CLEAR_APP_CACHE, and it only needs to actually run once.
        val actions = diagnoses
            .flatMap { it.repairActions }
            .distinctBy { it.type to it.targetPath }

        if (actions.isEmpty()) {
            return HealOutcome(triggered = false, steps = emptyList(), summary = "")
        }

        onStatus?.invoke("Issues Found.\nRepairing automatically...")

        val steps = actions.map { action ->
            val result = runCatching { CrashAnalyzer.executeRepair(action, gameVersion) }
                .getOrElse { e -> CrashAnalyzer.RepairResult(false, e.message ?: "Repair failed") }
            HealStep(action, result)
        }

        val succeeded = steps.count { it.result.success }
        val summary = buildString {
            append(
                if (succeeded == steps.size) "All $succeeded issue(s) repaired automatically."
                else "$succeeded of ${steps.size} issue(s) repaired automatically."
            )
            steps.forEach { step ->
                append("\n • ").append(step.action.label).append(": ").append(step.result.message)
            }
        }

        return HealOutcome(triggered = true, steps = steps, summary = summary)
    }
}
