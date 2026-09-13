package com.endiq.turtlelauncher.plugins.feature

import android.content.pm.ApplicationInfo

/**
 * A third-party "feature plugin": a separately installed app that opted in to appearing as
 * an extra TurtleLauncher Quick Action (see [FeaturePluginManager] for the discovery contract).
 *
 * Kotlin `val` properties already expose Java-visible `getDisplayName()`/`getDescription()`/
 * `getPackageName()`/`getApplicationInfo()` getters automatically - no manual getters needed.
 */
class FeaturePlugin(
    val packageName: String,
    val displayName: String,
    val description: String,
    val applicationInfo: ApplicationInfo
)
