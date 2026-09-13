package com.endiq.turtlelauncher.feature.version.install

import com.endiq.turtlelauncher.feature.log.Logging

/**
 * TurtleLauncher: placeholder for the two optional extras offered in the install popup
 * (InstallExtrasDialog) - Turtle Client and FPS Boost. Neither has a real mod resource
 * assigned yet (Modrinth slug, direct URL, bundled jar - whatever it ends up being).
 *
 * Once a resource exists for either entry, wire it into
 * InstallGameFragment.organizeInstallationTasks() the same way Addon.FABRIC_API/QSL
 * already do: download it, then move the resulting jar into the mods folder via an
 * InstallTaskItem, instead of calling [logPending] as a no-op.
 */
enum class ExtraModInstall(val displayName: String) {
    TURTLE_CLIENT("Turtle Client"),
    FPS_BOOST("FPS Boost");

    companion object {
        fun logPending(extra: ExtraModInstall) {
            Logging.w(
                "ExtraModInstall",
                "${extra.displayName} was selected in the install popup, but no mod resource is configured for it yet - skipping."
            )
        }
    }
}
