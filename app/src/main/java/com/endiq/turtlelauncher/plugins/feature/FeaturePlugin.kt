package com.endiq.turtlelauncher.plugins.feature

import android.content.pm.ApplicationInfo

data class FeaturePlugin(
    val packageName: String,
    val displayName: String,
    val description: String,
    val applicationInfo: ApplicationInfo,
)
