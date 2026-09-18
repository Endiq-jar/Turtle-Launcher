package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.feature.version.Version

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
