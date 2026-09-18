package com.endiq.turtlelauncher.feature.version

import android.os.Parcel
import android.os.Parcelable
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.mod.parser.ModChecker
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File

/**
 * A Minecraft version, distinguished by its version name.
 * @param versionsFolder version folder this version belongs to
 * @param versionPath path of the version
 * @param versionConfig config of the standalone version
 * @param isValid validity of the version
 */
class Version(
    private val versionsFolder: String,
    private val versionPath: String,
    private val versionConfig: VersionConfig,
    private val isValid: Boolean
) :Parcelable {
    /**
     * Controls whether the game launches with the current account treated as offline.
     */
    var offlineAccountLogin: Boolean = false

    /**
     * Result of the mod checks.
     */
    var modCheckResult: ModChecker.ModCheckResult? = null

    /**
     * @return the version folder this version belongs to
     */
    fun getVersionsFolder(): String = versionsFolder

    /**
     * @return the version folder
     */
    fun getVersionPath(): File = File(versionPath)

    /**
     * @return the version name
     */
    fun getVersionName(): String = getVersionPath().name

    /**
     * @return the version isolation config
     */
    fun getVersionConfig() = versionConfig

    /**
     * @return version validity: does the version json and folder exist
     */
    fun isValid() = isValid && getVersionPath().exists()

    /**
     * @return whether version isolation is enabled
     */
    fun isIsolation() = versionConfig.isIsolation()

    /**
     * @return the game folder path of the version (its version folder when isolation is on)
     */
    fun getGameDir(): File {
        return if (versionConfig.isIsolation()) versionConfig.getVersionPath()
        // Without version isolation a custom path may be used; when it is empty (unset), the
        // default game path (.minecraft/) is returned.
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