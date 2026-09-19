package com.endiq.turtlelauncher.feature.version.install

import com.endiq.turtlelauncher.feature.log.Logging

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
