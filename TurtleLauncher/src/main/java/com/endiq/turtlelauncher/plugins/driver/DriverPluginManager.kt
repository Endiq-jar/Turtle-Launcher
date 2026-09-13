package com.endiq.turtlelauncher.plugins.driver

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.gson.annotations.SerializedName
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.update.UpdateUtils
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.ZipUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * FCL 驱动器插件 + TurtleLauncher 本地驱动器插件（zip 安装，支持自动更新）
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
     * 初始化驱动器
     * @param reset 是否清除已有插件
     */
    fun initDriver(context: Context, reset: Boolean) {
        if (reset) driverList.clear()
        driverList.add(Driver("Turnip", context.applicationInfo.nativeLibraryDir))
        setDriverByName(AllSettings.driver.getValue())
    }

    /**
     * 通用 FCL 插件
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
     * 从本地 `/files/driver_plugins/` 目录下尝试解析驱动器插件（TurtleLauncher 新增）
     * @return 是否是符合要求的插件
     *
     * 驱动器插件文件夹格式（与渲染器插件保持一致的目录约定）：
     * driver_plugins/
     * ----文件夹名称/
     * --------meta.json (可选，存放驱动名称/版本信息)
     * --------libs/ (驱动 `.so` 文件的存放目录，与渲染器插件相同的架构子目录约定)
     * ------------arm64-v8a/
     * ----------------libvulkan_freedreno.so
     * ------------armeabi-v7a/
     * ------------x86/
     * ------------x86_64/
     *
     * 如果插件包没有按架构分目录，也允许直接将 `.so` 文件放在插件文件夹根目录下。
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
     * 导入本地驱动器插件（zip 格式，与渲染器插件导入逻辑一致）
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
