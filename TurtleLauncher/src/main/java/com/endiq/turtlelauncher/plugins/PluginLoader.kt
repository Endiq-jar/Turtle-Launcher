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

    private var lastSeenPackages: Set<String>? = null

    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    fun loadAllPlugins(context: Context, force: Boolean = false) {
        if (isInitialized && !force) return
        isInitialized = true

        DriverPluginManager.initDriver(context, force)
        if (force) {
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

        if (com.endiq.turtlelauncher.setting.AllSettings.autoCheckPluginUpdates.getValue() &&
            !com.endiq.turtlelauncher.setting.AllSettings.fastBoot.getValue() &&
            !com.endiq.turtlelauncher.task.TaskExecutors.isGameSessionActive) {
            com.endiq.turtlelauncher.feature.pluginupdate.PluginUpdateManager.checkForUpdates(context, force = false) { updates, _ ->
                if (updates.isEmpty()) return@checkForUpdates

                com.endiq.turtlelauncher.feature.log.Logging.i(
                    "PluginLoader",
                    "Found ${updates.size} renderer/driver plugin update(s) available upstream"
                )

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

    @JvmStatic
    fun rescanIfPluginsChanged(context: Context) {
        if (!isInitialized) {
            loadAllPlugins(context, force = false)
            return
        }
        val current = installedMainPackages(context)
        if (current.isEmpty() && !lastSeenPackages.isNullOrEmpty()) return
        if (current == lastSeenPackages) return
        com.endiq.turtlelauncher.feature.log.Logging.i("PluginLoader", "Installed-app set changed since last scan - rescanning plugin apps")
        loadAllPlugins(context, force = true)
    }
}
