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
 * 统一插件的加载，保证仅获取一次应用列表
 */
object PluginLoader {
    private var isInitialized: Boolean = false
    private const val PACKAGE_FLAGS =
        PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES

    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    fun loadAllPlugins(context: Context, force: Boolean = false) {
        if (isInitialized && !force) return
        isInitialized = true

        DriverPluginManager.initDriver(context, force)
        if (force) RendererPluginManager.clearPlugin()
        if (force) FeaturePluginManager.clearPlugin()

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

        //尝试解析本地渲染器插件
        PathManager.DIR_INSTALLED_RENDERER_PLUGIN.listFiles()?.let { files ->
            files.forEach { file ->
                if (!(file.isDirectory && RendererPluginManager.parseLocalPlugin(context, file))) {
                    //不符合要求的渲染器插件，将被删除！
                    FileUtils.deleteQuietly(file)
                }
            }
        }

        //尝试解析本地驱动器插件（TurtleLauncher 新增，支持驱动自动更新功能安装的插件）
        PathManager.DIR_INSTALLED_DRIVER_PLUGIN.listFiles()?.let { files ->
            files.forEach { file ->
                if (!(file.isDirectory && DriverPluginManager.parseLocalPlugin(file))) {
                    //不符合要求的驱动器插件，将被删除！
                    FileUtils.deleteQuietly(file)
                }
            }
        }

        if (RendererPluginManager.isAvailable()) {
            val failedToLoadList: MutableList<RendererPlugin> = mutableListOf()
            RendererPluginManager.getRendererList().forEach { rendererPlugin ->
                val isSuccess = Renderers.addRenderer(
                    object : RendererInterface {
                        override fun getRendererId(): String = rendererPlugin.id

                        override fun getUniqueIdentifier(): String = rendererPlugin.uniqueIdentifier

                        override fun getRendererName(): String = rendererPlugin.displayName

                        override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { rendererPlugin.env }

                        override fun getDlopenLibrary(): Lazy<List<String>> = lazy { rendererPlugin.dlopen }

                        override fun getRendererLibrary(): String = rendererPlugin.glName

                        override fun getRendererEGL(): String = rendererPlugin.eglName
                    }
                )
                if (!isSuccess) failedToLoadList.add(rendererPlugin)
            }
            if (failedToLoadList.isNotEmpty()) RendererPluginManager.removeRenderer(failedToLoadList)
        }

        //TurtleLauncher: 启动时静默检查渲染器/驱动器插件更新（受 5 分钟冷却、Fast Boot 与开关控制）
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
}
