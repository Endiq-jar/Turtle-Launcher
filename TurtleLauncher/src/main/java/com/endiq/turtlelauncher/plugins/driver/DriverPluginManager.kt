package com.endiq.turtlelauncher.plugins.driver

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.gson.annotations.SerializedName
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.update.UpdateUtils
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt
import net.endiq.launcher.Architecture
import net.endiq.launcher.Tools
import net.endiq.launcher.utils.ZipUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * FCL driver plugins plus TurtleLauncher local driver plugins (zip install, auto-update).
 * [FCL DriverPlugin.kt](https://github.com/FCL-Team/FoldCraftLauncher/blob/main/FCLauncher/src/main/java/com/tungsten/fclauncher/plugins/DriverPlugin.kt)
 */
object DriverPluginManager {
    private const val TAG = "DriverPluginManager"
    private val driverList: MutableList<Driver> = mutableListOf()

    /** Optional metadata shipped inside a local driver plugin zip as "meta.json". */
    private data class DriverMeta(
        val name: String? = null,
        @SerializedName("driverVersion") val driverVersion: String? = null,
        @SerializedName("libraryName") val libraryName: String? = null
    )

    @JvmStatic
    fun getDriverNameList(): List<String> = driverList.map { it.driver }

    private lateinit var currentDriver: Driver

    @JvmStatic
    fun setDriverByName(driverName: String) {
        currentDriver = driverList.find { it.driver == driverName } ?: driverList[0]
    }

    @JvmStatic
    fun getDriver(): Driver = currentDriver

    /**
     * Initialise the drivers.
     * @param reset whether existing plugins should be cleared
     */
    fun initDriver(context: Context, reset: Boolean) {
        if (reset) driverList.clear()
        driverList.add(Driver("Turnip", context.applicationInfo.nativeLibraryDir))
        setDriverByName(AllSettings.driver.getValue())
    }

    /**
     * Generic FCL plugin.
     */
    fun parsePlugin(info: ApplicationInfo) {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            val metaData = info.metaData ?: return
            if (metaData.getBoolean("fclPlugin", false)) {
                val driver = metaData.getString("driver") ?: return
                val nativeLibraryDir = info.nativeLibraryDir
                driverList.add(
                    Driver(
                        driver,
                        nativeLibraryDir
                    )
                )
            }
        }
    }

    /**
     * Try to parse driver plugins from the local `/files/driver_plugins/` directory
     * (a TurtleLauncher addition).
     * @return whether the plugin passes validation
     *
     * Driver plugin folder layout (same convention as renderer plugins):
     * driver_plugins/
     * ----<folder name>/
     * --------meta.json (optional, driver name/version info)
     * --------libs/ (holds the driver `.so` files, same architecture sub-folder layout as renderers)
     * ------------arm64-v8a/
     * ----------------libvulkan_freedreno.so
     * ------------armeabi-v7a/
     * ------------x86/
     * ------------x86_64/
     *
     * Plugin packs without architecture sub-folders may place the `.so` files directly in the
     * plugin folder root.
     */
    fun parseLocalPlugin(directory: File): Boolean {
        val archModel: String = UpdateUtils.getArchModel(Architecture.getDeviceArchitecture()) ?: return false

        val archLibsDir = File(directory, "libs/$archModel")
        val driverDir = when {
            archLibsDir.exists() && archLibsDir.isDirectory && hasNativeLibs(archLibsDir) -> archLibsDir
            hasNativeLibs(directory) -> directory
            else -> return false
        }

        val metaFile = File(directory, "meta.json")
        val meta: DriverMeta = if (metaFile.exists() && metaFile.isFile) {
            runCatching {
                Tools.GLOBAL_GSON.fromJson(metaFile.readText(), DriverMeta::class.java)
            }.getOrElse {
                Logging.w(TAG, "Failed to parse meta.json for local driver plugin ${directory.name}, using defaults")
                DriverMeta()
            }
        } else DriverMeta()

        val displayName = meta.name?.takeIf { it.isNotBlank() } ?: directory.name

        driverList.add(Driver(displayName, driverDir.absolutePath))
        Logging.i(TAG, "Loaded local driver plugin: $displayName (${meta.driverVersion ?: "unknown version"}) -> ${driverDir.absolutePath}")
        return true
    }

    private fun hasNativeLibs(dir: File): Boolean {
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".so") }?.isNotEmpty() == true
    }

    /**
     * Import a local driver plugin (zip format, same logic as renderer plugins).
     */
    fun importLocalDriverPlugin(pluginFile: File): Boolean {
        if (!pluginFile.exists() || !pluginFile.isFile) {
            Logging.i(TAG, "importLocalDriverPlugin: the compressed file does not exist or is not a valid file.")
            return false
        }

        return try {
            val pluginFolder = File(
                PathManager.DIR_INSTALLED_DRIVER_PLUGIN,
                StringUtilsKt.generateUniqueUUID(
                    { string -> string.replace("-", "").substring(0, 8) },
                    { uuid -> File(PathManager.DIR_INSTALLED_DRIVER_PLUGIN, uuid).exists() }
                )
            )

            ZipFile(pluginFile).use { pluginZip ->
                ZipUtils.zipExtract(pluginZip, "", pluginFolder)
            }

            if (!parseLocalPlugin(pluginFolder)) {
                FileUtils.deleteQuietly(pluginFolder)
                Logging.i(TAG, "importLocalDriverPlugin: extracted package did not contain a usable driver (no .so files found)")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Logging.i(TAG, "importLocalDriverPlugin: error: ${e.message}")
            false
        }
    }
}
