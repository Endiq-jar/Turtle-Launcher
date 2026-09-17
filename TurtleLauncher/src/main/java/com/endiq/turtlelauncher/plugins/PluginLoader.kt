package com.endiq.turtlelauncher.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.endiq.turtlelauncher.plugins.driver.DriverPluginManager
import com.endiq.turtlelauncher.plugins.feature.FeaturePluginManager
import com.endiq.turtlelauncher.plugins.renderer.RendererPlugin
import com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager
import com.endiq.turtlelauncher.renderer.RendererInterface
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.utils.path.PathManager
import org.apache.commons.io.FileUtils


/**
 * Centralises plugin loading so the app list is fetched only once.
 */
object PluginLoader {
    private var isInitialized: Boolean = false
    private const val PACKAGE_FLAGS =
        PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES

    /**
     * The set of installed packages that expose a MAIN activity, as seen by the last scan.
     * Compared by [rescanIfPluginsChanged] so a plugin app installed (or uninstalled) while
     * TurtleLauncher is already running gets picked up without a full app restart - "any
     * plugin app install should work in the launcher" must hold no matter where the install
     * came from (this launcher's own Settings page, a file manager, a store...).
     */
    private var lastSeenPackages: Set<String>? = null

    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    fun loadAllPlugins(context: Context, force: Boolean = false) {
        if (isInitialized && !force) return
        isInitialized = true

        DriverPluginManager.initDriver(context, force)
        if (force) {
            // A re-scan must replace the previously registered plugin renderers, not stack
            // on top of them: Renderers.addRenderer() rejects duplicate unique identifiers,
            // so without this removal every still-installed plugin would collide with its
            // own previous registration, fail to re-register and get dropped from the
            // plugin list - leaving it unusable until a restart. Clear the old registrations
            // first (also invalidating the compatible-renderer cache and, if it was one of
            // them, the current selection, which the next setCurrentRenderer call recovers
            // or falls back from).
            Renderers.removeRenderers(RendererPluginManager.getRendererList().map { it.uniqueIdentifier })
            RendererPluginManager.clearPlugin()
            FeaturePluginManager.clearPlugin()
        }

        val queryIntentActivities =
            context.packageManager.queryIntentActivities(
                Intent("android.intent.action.MAIN"),
                PACKAGE_FLAGS
            )
        queryIntentActivities.forEach {
            val applicationInfo = it.activityInfo.applicationInfo
            DriverPluginManager.parsePlugin(applicationInfo)
            RendererPluginManager.parseApkPlugin(context, applicationInfo)
            FeaturePluginManager.parseApkPlugin(context, applicationInfo)
        }

        // Try to parse the local renderer plugins.
        PathManager.DIR_INSTALLED_RENDERER_PLUGIN.listFiles()?.let { files ->
            files.forEach { file ->
                if (!(file.isDirectory && RendererPluginManager.parseLocalPlugin(context, file))) {
                    // Renderer plugins that fail validation get deleted.
                    FileUtils.deleteQuietly(file)
                }
            }
        }

        // Try to parse the local driver plugins (a TurtleLauncher addition that supports
        // plugins installed by the driver auto-update feature).
        PathManager.DIR_INSTALLED_DRIVER_PLUGIN.listFiles()?.let { files ->
            files.forEach { file ->
                if (!(file.isDirectory && DriverPluginManager.parseLocalPlugin(file))) {
                    // Driver plugins that fail validation get deleted.
                    FileUtils.deleteQuietly(file)
                }
            }
        }

        if (RendererPluginManager.isAvailable()) {
            val failedToLoadList: MutableList<RendererPlugin> = mutableListOf()
            RendererPluginManager.getRendererList().forEach { rendererPlugin ->
                // Plugin libraries live inside the plugin (its APK's nativeLibraryDir or
                // the local plugin folder), NOT in this launcher's own jniLibs folder - so
                // expose them as absolute paths. Renderers.hasRequiredLibrary() verifies
                // that exact file exists before letting a renderer into the picker, and a
                // bare library name would make it look in the launcher's folder, miss it,
                // and silently drop the plugin renderer (that's what hid the MobileGlues
                // app's renderer from the list). JREUtils.loadGraphicsLibrary() already
                // resolves the selected plugin's library as path/glName at launch time, so
                // absolute paths here only affect the picker/validation side.
                fun resolveInPluginDir(libName: String): String =
                    if (libName.startsWith("/")) libName
                    else "${rendererPlugin.path}/$libName"

                val isSuccess = Renderers.addRenderer(
                    object : RendererInterface {
                        override fun getRendererId(): String = rendererPlugin.id

                        override fun getUniqueIdentifier(): String = rendererPlugin.uniqueIdentifier

                        override fun getRendererName(): String = rendererPlugin.displayName

                        override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { rendererPlugin.env }

                        override fun getDlopenLibrary(): Lazy<List<String>> = lazy { rendererPlugin.dlopen }

                        override fun getRendererLibrary(): String = resolveInPluginDir(rendererPlugin.glName)

                        override fun getRendererEGL(): String =
                            if (rendererPlugin.eglName == "libEGL.so") rendererPlugin.eglName
                            else resolveInPluginDir(rendererPlugin.eglName)
                    }
                )
                if (!isSuccess) failedToLoadList.add(rendererPlugin)
            }
            if (failedToLoadList.isNotEmpty()) RendererPluginManager.removeRenderer(failedToLoadList)
        }

        // Remember what's installed right now, so rescanIfPluginsChanged() can tell later
        // whether a plugin app appeared or disappeared in the meantime.
        lastSeenPackages = installedMainPackages(context)

        // TurtleLauncher: silently check renderer/driver plugin updates at startup
        // (gated by the 5 minute cooldown, Fast Boot and the setting toggle).
        // Background Services (item 20) - "pause update checker": loadAllPlugins() runs
        // again for real the moment MainActivity (a separate :game process) reaches this
        // same BaseActivity.onCreate() codepath at the start of every session, since
        // isInitialized is per-process state. The plugin *load* below is left untouched
        // (a plugin renderer/driver genuinely has to load from disk to render at all), but
        // this upstream network "is there a newer one" check is pure overhead during an
        // active game session - it never affects what's about to render, only what would
        // show up next time someone opens Settings. Gate it on the same game-session flag
        // TaskExecutors already exposes rather than inventing new state.
        if (com.endiq.turtlelauncher.setting.AllSettings.autoCheckPluginUpdates.getValue() &&
            !com.endiq.turtlelauncher.setting.AllSettings.fastBoot.getValue() &&
            !com.endiq.turtlelauncher.task.TaskExecutors.isGameSessionActive) {
            com.endiq.turtlelauncher.feature.pluginupdate.PluginUpdateManager.checkForUpdates(context, force = false) { updates, _ ->
                if (updates.isEmpty()) return@checkForUpdates

                com.endiq.turtlelauncher.feature.log.Logging.i(
                    "PluginLoader",
                    "Found ${updates.size} renderer/driver plugin update(s) available upstream"
                )

                // .zip plugins import silently (no OS interaction needed) so those install
                // automatically here. .apk companion-app plugins can only go through the real
                // Android package installer, which requires the user to actually tap confirm -
                // firing that unprompted on every app launch would be a surprise system dialog
                // the user never asked for, so those are logged only; ExperimentalSettingsFragment
                // is where the user reviews and installs them on purpose.
                val (autoInstallable, manualOnly) = updates.partition { !it.isApk }
                if (manualOnly.isNotEmpty()) {
                    com.endiq.turtlelauncher.feature.log.Logging.i(
                        "PluginLoader",
                        "${manualOnly.size} of those are companion-app installs (${manualOnly.joinToString { it.assetName }}) - open Settings to install"
                    )
                }
                autoInstallable.forEach { asset ->
                    com.endiq.turtlelauncher.feature.pluginupdate.PluginUpdateManager.downloadAndInstall(context, asset) { success, message ->
                        com.endiq.turtlelauncher.feature.log.Logging.i("PluginLoader", "Auto-update ${asset.assetName}: $message")
                    }
                }
            }
        }
    }

    /** The set of installed packages exposing a MAIN activity - the superset any plugin
     *  (renderer/driver/feature) has to be part of before it can even be discovered. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun installedMainPackages(context: Context): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(
            Intent("android.intent.action.MAIN"),
            PackageManager.GET_META_DATA
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }.getOrDefault(emptySet())

    /**
     * Re-scans installed plugin apps when - and only when - the set of installed apps
     * actually changed since the last scan. Called from LauncherActivity.onResume(), i.e.
     * every time the user comes back to the home screen: from the Android package installer
     * after confirming a plugin APK install, from a store, from a file manager - anywhere.
     * Without this, a plugin installed while TurtleLauncher is already running only becomes
     * visible after a full app restart (and an uninstalled one stays wrongly selectable),
     * because loadAllPlugins() runs once per process by design.
     */
    @JvmStatic
    fun rescanIfPluginsChanged(context: Context) {
        if (!isInitialized) {
            loadAllPlugins(context, force = false)
            return
        }
        val current = installedMainPackages(context)
        // A query that comes back empty while we previously saw apps is a transient
        // PackageManager hiccup, not a mass uninstall - don't let it wipe the registered
        // plugins via a forced re-scan.
        if (current.isEmpty() && !lastSeenPackages.isNullOrEmpty()) return
        if (current == lastSeenPackages) return
        com.endiq.turtlelauncher.feature.log.Logging.i("PluginLoader", "Installed-app set changed since last scan - rescanning plugin apps")
        loadAllPlugins(context, force = true)
    }
}
