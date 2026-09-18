package com.endiq.turtlelauncher.plugins.renderer

import android.content.Context
import android.content.pm.ApplicationInfo
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.update.UpdateUtils
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt
import net.endiq.launcher.Architecture
import net.endiq.launcher.Tools
import net.endiq.launcher.utils.ZipUtils
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * FCL and TurtleLauncher renderer plugins, including local renderer plugins.
 * [FCL Renderer Plugin](https://github.com/FCL-Team/FCLRendererPlugin)
 */
object RendererPluginManager {
    private val rendererPluginList: MutableList<RendererPlugin> = mutableListOf()
    private val apkRendererPluginList: MutableList<ApkRendererPlugin> = mutableListOf()
    private val localRendererPluginList: MutableList<LocalRendererPlugin> = mutableListOf()

    /**
     * Get all renderers loaded by the current renderer plugin.
     */
    @JvmStatic
    fun getRendererList() = rendererPluginList

    /**
     * Remove some of the loaded renderers.
     */
    @JvmStatic
    fun removeRenderer(rendererPlugins: Collection<RendererPlugin>) {
        rendererPluginList.removeAll(rendererPlugins)
    }

    /**
     * Get all renderers loaded by the current local renderer plugin.
     */
    @JvmStatic
    fun getAllLocalRendererList() = localRendererPluginList

    /**
     * @return true when usable
     */
    @JvmStatic
    fun isAvailable(): Boolean {
        return rendererPluginList.isNotEmpty()
    }

    /**
     * The renderers loaded by the currently selected renderer plugin.
     * Decided from the unique id of the renderer chosen by the renderer manager.
     */
    @JvmStatic
    val selectedRendererPlugin: RendererPlugin?
        get() {
            val currentRenderer = runCatching {
                Renderers.getCurrentRenderer().getUniqueIdentifier()
            }.getOrNull()
            return rendererPluginList.find { it.uniqueIdentifier == currentRenderer }
        }

    /**
     * Clear the renderer plugins.
     */
    fun clearPlugin() {
        rendererPluginList.clear()
        apkRendererPluginList.clear()
        localRendererPluginList.clear()
    }

    /**
     * Whether the current renderer plugin ships a config (software plugins, package whitelist).
     */
    @JvmStatic
    fun getConfigurablePluginOrNull(rendererUniqueIdentifier: String): RendererPlugin? {
        val renderer = apkRendererPluginList.find { it.uniqueIdentifier == rendererUniqueIdentifier }
        return renderer?.takeIf { it.packageName in setOf(
                "com.bzlzhh.plugin.ngg",
                "com.bzlzhh.plugin.ngg.angleless",
                "com.fcl.plugin.mobileglues"
            ) }
    }

    /**
     * Parse TurtleLauncher / FCL renderer plugins.
     */
    fun parseApkPlugin(context: Context, info: ApplicationInfo) {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            val metaData = info.metaData ?: return
            if (
                metaData.getBoolean("fclPlugin", false) ||
                metaData.getBoolean("turtleRendererPlugin", false)
            ) {
                val rendererString = metaData.getString("renderer") ?: return
                val des = metaData.getString("des") ?: return
                val turtleEnvString = metaData.getString("turtleEnv")
                    ?: metaData.getString("pojavEnv")
                    ?: metaData.getString("boatEnv")
                    ?: return
                val nativeLibraryDir = info.nativeLibraryDir
                val renderer = rendererString.split(":")

                var rendererId: String = renderer[0]
                val envList = mutableMapOf<String, String>()
                val dlopenList = mutableListOf<String>()
                turtleEnvString.split(":").forEach { envString ->
                    if (envString.contains("=")) {
                        val stringList = envString.split("=")
                        val key = stringList[0]
                        val value = stringList[1]
                        when (key) {
                            "POJAV_RENDERER" -> rendererId = value
                            "DLOPEN" -> {
                                value.split(",").forEach { lib ->
                                    dlopenList.add(lib)
                                }
                            }
                            "LIB_MESA_NAME", "MESA_LIBRARY" -> envList[key] = "$nativeLibraryDir/$value"
                            else -> envList[key] = value
                        }
                    }
                }

                val packageName = info.packageName

                val minMCVer = metaData.getString("minMCVer")?.takeIf { it.isNotBlank() }
                val maxMCVer = metaData.getString("maxMCVer")?.takeIf { it.isNotBlank() }

                val plugin = ApkRendererPlugin(
                    rendererId,
                    "$des (${
                        context.getString(
                            R.string.setting_renderer_from_plugins,
                            runCatching {
                                context.packageManager.getApplicationLabel(info)
                            }.getOrElse {
                                context.getString(R.string.generic_unknown)
                            }
                        )
                    })",
                    packageName,
                    renderer[1],
                    renderer[2].progressEglName(nativeLibraryDir),
                    nativeLibraryDir,
                    envList,
                    dlopenList,
                    packageName,
                    minMCVer,
                    maxMCVer
                )

                rendererPluginList.add(plugin)
                apkRendererPluginList.add(plugin)
            }
        }
    }

    /**
     * Try to parse renderer plugins from the local `/files/renderer_plugins/` directory.
     * @return whether the plugin passes validation
     *
     * Renderer folder layout:
     * renderer_plugins/
     * ----<folder name>/
     * --------renderer_config.json (renderer details config file)
     * --------libs/ (holds the renderer `.so` files)
     * ------------arm64-v8a/ (arm64 architecture)
     * ----------------renderer library .so
     * ------------armeabi-v7a/ (arm32 architecture)
     * ----------------renderer library .so
     * ------------x86/ (x86 architecture)
     * ----------------renderer library .so
     * ------------x86_64/ (x86_64 architecture)
     * ----------------renderer library .so
     */
    fun parseLocalPlugin(context: Context, directory: File): Boolean {
        val archModel: String = UpdateUtils.getArchModel(Architecture.getDeviceArchitecture()) ?: return false
        val libsDirectory: File = File(directory, "libs/$archModel").takeIf { it.exists() && it.isDirectory } ?: return false
        val rendererConfigFile: File = File(directory, "config").takeIf { it.exists() && it.isFile } ?: return false
        val rendererConfig: RendererConfig = runCatching {
            Tools.GLOBAL_GSON.fromJson(readLocalRendererPluginConfig(rendererConfigFile), RendererConfig::class.java)
        }.getOrElse { e ->
            Logging.e("LocalRendererPlugin", "Failed to parse the configuration file", e)
            return false
        }
        val uniqueIdentifier = directory.name
        rendererConfig.run {
            val libPath = libsDirectory.absolutePath

            val plugin = LocalRendererPlugin(
                rendererId,
                "$rendererDisplayName (${
                    context.getString(
                        R.string.setting_renderer_from_plugins,
                        uniqueIdentifier
                    )
                })",
                uniqueIdentifier,
                glName,
                eglName.progressEglName(libPath),
                libPath,
                turtleEnv.filter { it.key != "POJAV_RENDERER" },
                dlopenList ?: emptyList(),
                directory
            )

            rendererPluginList.add(plugin)
            localRendererPluginList.add(plugin)
        }
        return true
    }

    private fun String.progressEglName(libPath: String): String =
        if (startsWith("/")) "$libPath$this"
        else this

    private fun readLocalRendererPluginConfig(configFile: File): String {
        return FileInputStream(configFile).use { fileInputStream ->
            DataInputStream(fileInputStream).use { dataInputStream ->
                dataInputStream.readUTF()
            }
        }
    }

    /**
     * Import a local renderer plugin.
     */
    fun importLocalRendererPlugin(pluginFile: File): Boolean {
        if (!pluginFile.exists() || !pluginFile.isFile) {
            Logging.i("importLocalRendererPlugin", "The compressed file does not exist or is not a valid file.")
            return false
        }

        return try {
            ZipFile(pluginFile).use { pluginZip ->
                val configEntry = pluginZip.entries().asSequence().find { it.name == "config" }
                    ?: throw IllegalArgumentException("The plugin package does not meet the requirements!")

                pluginZip.getInputStream(configEntry).use { inputStream ->
                    DataInputStream(inputStream).use { dataInputStream ->
                        val configContent = dataInputStream.readUTF()
                        Tools.GLOBAL_GSON.fromJson(configContent, RendererConfig::class.java)
                    }
                }

                val pluginFolder = File(
                    PathManager.DIR_INSTALLED_RENDERER_PLUGIN,
                    StringUtilsKt.generateUniqueUUID(
                        { string ->
                            string.replace("-", "").substring(0, 8)
                        },
                        { uuid ->
                            File(PathManager.DIR_INSTALLED_RENDERER_PLUGIN, uuid).exists()
                        }
                    )
                )

                ZipUtils.zipExtract(pluginZip, "", pluginFolder)
            }
            true
        } catch (e: Exception) {
            Logging.i("importLocalRendererPlugin", "Error: ${e.message}")
            false
        }
    }
}