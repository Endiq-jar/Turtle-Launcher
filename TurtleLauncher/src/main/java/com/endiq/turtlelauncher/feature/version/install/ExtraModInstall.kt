package com.endiq.turtlelauncher.feature.version.install

import com.endiq.turtlelauncher.feature.log.Logging

/**
 * TurtleLauncher: the two optional extras offered in the install popup
 * (InstallExtrasDialog) - Turtle Client and FPS Boost.
 *
 * Turtle Client is now fully wired: InstallGameFragment.organizeInstallationTasks()
 * downloads it automatically via TurtleClientDownloadTask (Modrinth slug
 * "turtleclient", resolved per-mcVersion) and moves the jar into the mods folder,
 * the same shape Addon.FABRIC_API/QSL already use.
 *
 * FPS Boost still has no real mod resource assigned (Modrinth slug, direct URL,
 * bundled jar - whatever it ends up being) - [logPending] remains a no-op for it
 * until one exists.
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
