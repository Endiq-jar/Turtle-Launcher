package com.endiq.turtlelauncher.domain.model

/**
 * This build intentionally exposes one Minecraft release. Keeping the policy in the
 * domain layer lets both the UI and repositories enforce the same restriction.
 */
object MinecraftSupport {
    const val SUPPORTED_VERSION = "26.3"

    fun isSupported(versionId: String): Boolean =
        versionId.trim() == SUPPORTED_VERSION
}
