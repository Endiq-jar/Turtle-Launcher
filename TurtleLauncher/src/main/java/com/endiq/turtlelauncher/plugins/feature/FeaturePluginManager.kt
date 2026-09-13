package com.endiq.turtlelauncher.plugins.feature

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Discovers and registers "feature plugins": ordinary installed Android apps that opt in to
 * appearing as an extra TurtleLauncher Quick Action by declaring `<meta-data>` on their own
 * launcher activity - nothing else required, no SDK/AAR to depend on, no AIDL/IPC service to
 * implement, and no TurtleLauncher code or APK update needed to add a new one:
 *
 * ```xml
 * <activity android:name=".MainActivity" ...>
 *     <intent-filter>
 *         <action android:name="android.intent.action.MAIN"/>
 *         <category android:name="android.intent.category.LAUNCHER"/>
 *     </intent-filter>
 *     <meta-data android:name="turtleLauncherFeaturePlugin" android:value="true"/>
 *     <meta-data android:name="turtleLauncherFeatureName" android:value="My Feature"/>
 *     <meta-data android:name="turtleLauncherFeatureDescription" android:value="What it does"/>
 * </activity>
 * ```
 *
 * `turtleLauncherFeatureName`/`turtleLauncherFeatureDescription` are optional - the app's own
 * launcher label is used as a fallback name, and the description row is hidden if omitted.
 * Tapping the resulting Quick Actions row just starts that same launcher activity
 * (`PackageManager.getLaunchIntentForPackage`) - the plugin is a completely standalone app,
 * TurtleLauncher only discovers it and gives it a shortcut.
 *
 * Mirrors the existing renderer/driver plugin discovery pattern in
 * [com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager] /
 * [com.endiq.turtlelauncher.plugins.driver.DriverPluginManager] - same
 * `queryIntentActivities(MAIN)` scan already run by [com.endiq.turtlelauncher.plugins.PluginLoader],
 * just for arbitrary launcher *features* instead of renderers/drivers.
 */
object FeaturePluginManager {
    private const val META_IS_FEATURE_PLUGIN = "turtleLauncherFeaturePlugin"
    private const val META_FEATURE_NAME = "turtleLauncherFeatureName"
    private const val META_FEATURE_DESCRIPTION = "turtleLauncherFeatureDescription"

    private val featurePluginList: MutableList<FeaturePlugin> = mutableListOf()

    @JvmStatic
    fun getFeaturePluginList(): List<FeaturePlugin> = featurePluginList

    @JvmStatic
    fun clearPlugin() {
        featurePluginList.clear()
    }

    /**
     * Registers [info] as a feature plugin if - and only if - it declares
     * `turtleLauncherFeaturePlugin=true` in its own manifest meta-data. Called once per
     * MAIN/DEFAULT activity [com.endiq.turtlelauncher.plugins.PluginLoader] already finds
     * while scanning for renderer/driver plugins; safe to call on every installed app since
     * everything except the explicit opt-in is ignored.
     */
    @JvmStatic
    fun parseApkPlugin(context: Context, info: ApplicationInfo) {
        if (info.packageName == context.packageName) return // never surface TurtleLauncher itself
        val metaData = info.metaData ?: return
        if (!metaData.getBoolean(META_IS_FEATURE_PLUGIN, false)) return

        // A plugin app can show up more than once if it declares multiple MAIN/DEFAULT
        // activities - only register its package once.
        if (featurePluginList.any { it.packageName == info.packageName }) return

        val label = metaData.getString(META_FEATURE_NAME)
            ?: runCatching { context.packageManager.getApplicationLabel(info).toString() }
                .getOrElse { info.packageName }
        val description = metaData.getString(META_FEATURE_DESCRIPTION) ?: ""

        featurePluginList.add(FeaturePlugin(info.packageName, label, description, info))
    }
}
