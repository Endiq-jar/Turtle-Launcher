package com.endiq.turtlelauncher.plugins.feature

import android.content.Context
import android.content.Intent

/** Discovers installed apps that explicitly opt into the old Turtle home quick-action rail. */
object FeaturePluginManager {
    private const val META_IS_FEATURE_PLUGIN = "turtleLauncherFeaturePlugin"
    private const val META_FEATURE_NAME = "turtleLauncherFeatureName"
    private const val META_FEATURE_DESCRIPTION = "turtleLauncherFeatureDescription"

    fun scan(context: Context): List<FeaturePlugin> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .mapNotNull { info ->
                val meta = runCatching { packageManager.getApplicationInfo(info.packageName, 128).metaData }
                    .getOrNull() ?: return@mapNotNull null
                if (!meta.getBoolean(META_IS_FEATURE_PLUGIN, false)) return@mapNotNull null
                val label = meta.getString(META_FEATURE_NAME)
                    ?: runCatching { packageManager.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName)
                FeaturePlugin(
                    packageName = info.packageName,
                    displayName = label,
                    description = meta.getString(META_FEATURE_DESCRIPTION).orEmpty(),
                    applicationInfo = info,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }
}
