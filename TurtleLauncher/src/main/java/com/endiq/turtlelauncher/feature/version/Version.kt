package com.endiq.turtlelauncher.feature.version

import android.os.Parcel
import android.os.Parcelable
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.mod.parser.ModChecker
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import java.io.File

/**
 * Minecraft 版本，由版本名称进行区分
 * @param versionsFolder 版本所属的版本文件夹
 * @param versionPath 版本的路径
 * @param versionConfig 独立版本的配置
 * @param isValid 版本的有效性
 */
class Version(
    private val versionsFolder: String,
    private val versionPath: String,
    private val versionConfig: VersionConfig,
    private val isValid: Boolean
) :Parcelable {
    /**
     * 控制是否将当前账号视为离线账号启动游戏
     */
    var offlineAccountLogin: Boolean = false

    /**
     * 模组检查结果
     */
    var modCheckResult: ModChecker.ModCheckResult? = null

    /**
     * @return 获取版本所属的版本文件夹
     */
    fun getVersionsFolder(): String = versionsFolder

    /**
     * @return 获取版本文件夹
     */
    fun getVersionPath(): File = File(versionPath)

    /**
     * @return 获取版本名称
     */
    fun getVersionName(): String = getVersionPath().name

    /**
     * @return 获取版本隔离配置
     */
    fun getVersionConfig() = versionConfig

    /**
     * @return 版本的有效性：是否存在版本JSON文件、版本文件夹是否存在
     */
    fun isValid() = isValid && getVersionPath().exists()

    /**
     * @return 是否开启了版本隔离
     */
    fun isIsolation() = versionConfig.isIsolation()

    /**
     * @return 获取版本的游戏文件夹路径（若开启了版本隔离，则路径为版本文件夹）
     */
    fun getGameDir(): File {
        return if (versionConfig.isIsolation()) versionConfig.getVersionPath()
        //未开启版本隔离可以使用自定义路径，如果自定义路径为空（则为未设置），那么返回默认游戏路径（.minecraft/）
        else if (versionConfig.getCustomPath().isNotEmpty()) File(versionConfig.getCustomPath())
        else File(ProfilePathHome.getGameHome())
    }

    private fun String.getValueOrDefault(default: String): String = this.takeIf { it.isNotEmpty() } ?: default

    fun getRenderer(): String = versionConfig.getRenderer().getValueOrDefault(AllSettings.renderer.getValue())

    fun getDriver(): String = versionConfig.getDriver().getValueOrDefault(AllSettings.driver.getValue())

    fun getJavaDir(): String = versionConfig.getJavaDir().getValueOrDefault(AllSettings.defaultRuntime.getValue())

    fun getJavaArgs(): String = versionConfig.getJavaArgs().getValueOrDefault(AllSettings.javaArgs.getValue())

    fun getControl(): String {
        val configControl = versionConfig.getControl().removeSuffix("./")
        return if (configControl.isNotEmpty()) File(PathManager.DIR_CTRLMAP_PATH, configControl).absolutePath
        else File(AllSettings.defaultCtrl.getValue()).absolutePath
    }

    fun getCustomInfo(): String = versionConfig.getCustomInfo().getValueOrDefault(AllSettings.versionCustomInfo.getValue())
        .replace("[zl_version]", ZHTools.getVersionName())

    fun getVersionInfo(): VersionInfo? {
        return runCatching {
            val infoFile = File(VersionsManager.getTurtleVersionPath(this), "VersionInfo.json")
            Tools.GLOBAL_GSON.fromJson(Tools.read(infoFile), VersionInfo::class.java)
        }.getOrElse { null }
    }

    private fun Boolean.getInt(): Int = if (this) 1 else 0

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStringList(listOf(versionsFolder, versionPath))
        dest.writeParcelable(versionConfig, flags)
        dest.writeInt(isValid.getInt())
        dest.writeInt(offlineAccountLogin.getInt())
        dest.writeParcelable(modCheckResult, flags)
    }

    companion object CREATOR : Parcelable.Creator<Version> {
        private fun Int.toBoolean(): Boolean = this != 0

        override fun createFromParcel(parcel: Parcel): Version {
            val stringList = ArrayList<String>()
            parcel.readStringList(stringList)
            // A corrupt/truncated Parcel used to crash here twice over: readParcelable()
            // can return null (non-null assertion -> NPE) and stringList[] throws
            // IndexOutOfBounds when short. Fall back to defaults so a bad Intent
            // extra can't kill the app.
            val versionsFolder = stringList.getOrElse(0) { "" }
            val versionPath = stringList.getOrElse(1) { "" }
            val versionConfig = parcel.readParcelable<VersionConfig>(VersionConfig::class.java.classLoader)
                ?: VersionConfig(File(versionPath))
            val isValid = parcel.readInt().toBoolean()
            val offlineAccount = parcel.readInt().toBoolean()
            val modCheckResult = parcel.readParcelable<ModChecker.ModCheckResult>(ModChecker.ModCheckResult::class.java.classLoader)

            return Version(versionsFolder, versionPath, versionConfig, isValid).apply {
                offlineAccountLogin = offlineAccount
                this.modCheckResult = modCheckResult
            }
        }

        override fun newArray(size: Int): Array<Version?> {
            return arrayOfNulls(size)
        }
    }
}