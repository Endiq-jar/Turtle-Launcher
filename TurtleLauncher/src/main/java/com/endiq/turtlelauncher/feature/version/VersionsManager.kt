package com.endiq.turtlelauncher.feature.version

import android.content.Context
import com.endiq.turtlelauncher.InfoDistributor
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.event.single.RefreshVersionsEvent
import com.endiq.turtlelauncher.event.single.RefreshVersionsEvent.MODE.END
import com.endiq.turtlelauncher.event.single.RefreshVersionsEvent.MODE.START
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.favorites.FavoritesVersionUtils
import com.endiq.turtlelauncher.feature.version.utils.VersionInfoUtils
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.EditTextDialog
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.stringutils.SortStrings
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList


/**
 * Manager of all versions.
 * @see Version
 */
object VersionsManager {
    private val versions = CopyOnWriteArrayList<Version>()

    /**
     * @return the current game info
     */
    lateinit var currentGameInfo: CurrentGameInfo
        private set

    private val coroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("VersionsManager"))
    private val refreshMutex = Mutex()
    private var isRefreshing: Boolean = false
    private var lastRefreshTime = 0L

    /**
     * @return whether a refresh is allowed
     */
    @JvmStatic
    fun canRefresh() = !isRefreshing && ZHTools.getCurrentTimeMillis() - lastRefreshTime > 500

    /**
     * @return all version data
     */
    fun getVersions() = versions.toList()

    /**
     * Check whether the version already exists.
     */
    fun isVersionExists(versionName: String, checkJson: Boolean = false): Boolean {
        val folder = File(ProfilePathHome.getVersionsHome(), versionName)
        // Besides the version folder, make sure its version json exists as well.
        return if (checkJson) File(folder, "${folder.name}.json").exists()
        else folder.exists()
    }

    /**
     * Refresh the version list asynchronously; completion is broadcast through an event that
     * does not run on the UI thread.
     * @param tag marks who started the version refresh, handy for debugging
     * @see com.endiq.turtlelauncher.event.single.RefreshVersionsEvent
     */
    fun refresh(tag: String, refreshVersionInfo: Boolean = false) {
        Logging.i("VersionsManager", "$tag initiated the refresh version task")
        coroutineScope.launch {
            refreshMutex.withLock {
                lastRefreshTime = ZHTools.getCurrentTimeMillis()
                handleRefreshOperation(refreshVersionInfo)
            }
        }
    }

    private fun handleRefreshOperation(refreshVersionInfo: Boolean) {
        isRefreshing = true
        EventBus.getDefault().post(RefreshVersionsEvent(START))

        versions.clear()

        val versionsHome: String = ProfilePathHome.getVersionsHome()
        File(versionsHome).listFiles()?.forEach { versionFile ->
            runCatching {
                processVersionFile(versionsHome, versionFile, refreshVersionInfo)
            }
        }

        versions.sortWith { o1, o2 ->
            var sort = -SortStrings.compareClassVersions(
                o1.getVersionInfo()?.minecraftVersion ?: o1.getVersionName(),
                o2.getVersionInfo()?.minecraftVersion ?: o2.getVersionName()
            )
            if (sort == 0) sort = SortStrings.compareChar(o1.getVersionName(), o2.getVersionName())
            sort
        }

        currentGameInfo = CurrentGameInfo.refreshCurrentInfo()

        // Notify through the event bus that versions were refreshed.
        EventBus.getDefault().post(RefreshVersionsEvent(END))
        isRefreshing = false
    }

    private fun processVersionFile(versionsHome: String, versionFile: File, refreshVersionInfo: Boolean) {
        if (versionFile.exists() && versionFile.isDirectory) {
            var isVersion = false

            // A folder counts as a version when its .json file exists.
            val jsonFile = File(versionFile, "${versionFile.name}.json")
            if (jsonFile.exists() && jsonFile.isFile) {
                isVersion = true
                if (jsonFile.length() > 0) {
                    val versionInfoFile = File(getTurtleVersionPath(versionFile), "VersionInfo.json")
                    if (refreshVersionInfo) FileUtils.deleteQuietly(versionInfoFile)
                    if (!versionInfoFile.exists()) {
                        VersionInfoUtils.parseJson(jsonFile)?.save(versionFile)
                    }
                } else {
                    // TurtleLauncher: an empty (0-byte) version json is a real, if rare, state -
                    // e.g. a version whose json is still being written by an in-progress install/
                    // extraction at the moment a refresh happens to run concurrently. Parsing it
                    // was guaranteed to fail (Gson turns an empty string into JsonNull, and
                    // .asJsonObject on that throws "Not a JSON Object: null"), so every refresh
                    // was burning a parse-and-fail cycle plus a full stack trace log for a file
                    // that was never going to succeed. Skip it instead; the next refresh will
                    // pick it up once the write completes.
                    Logging.w("VersionsManager", "Skipping empty version json: ${jsonFile.path}")
                }
            }

            val versionConfig = VersionConfig.parseConfig(versionFile)

            val version = Version(
                versionsHome,
                versionFile.absolutePath,
                versionConfig,
                isVersion
            )
            versions.add(version)

            Logging.i("VersionsManager", "Identified and added version: ${version.getVersionName()}, " +
                    "Path: (${version.getVersionPath()}), " +
                    "Info: ${version.getVersionInfo()?.getInfoString()}")
        }
    }

    /**
     * @return the current version
     */
    fun getCurrentVersion(): Version? {
        if (versions.isEmpty()) return null

        fun returnVersionByFirst(): Version? {
            return versions.find { it.isValid() }?.apply {
                // Make sure the version is valid.
                saveCurrentVersion(getVersionName())
            }
        }

        return runCatching {
            val versionString = currentGameInfo.version
            getVersion(versionString) ?: run {
                return returnVersionByFirst()
            }
        }.getOrElse { e ->
            Logging.e("Get Current Version", Tools.printToString(e))
            returnVersionByFirst()
        }
    }

    /**
     * @return whether a version with that name exists
     */
    fun checkVersionExistsByName(versionName: String?) =
        versionName?.let { name -> versions.any { it.getVersionName() == name } } ?: false

    /**
     * @return the Turtle launcher version marker folder
     */
    fun getTurtleVersionPath(version: Version) = File(version.getVersionPath(), InfoDistributor.LAUNCHER_NAME)

    /**
     * @return the Turtle launcher version marker folder for a directory
     */
    fun getTurtleVersionPath(folder: File) = File(folder, InfoDistributor.LAUNCHER_NAME)

    /**
     * @return the Turtle launcher version marker folder for a name
     */
    fun getTurtleVersionPath(name: String) = File(getVersionPath(name), InfoDistributor.LAUNCHER_NAME)

    /**
     * @return the icon configured for the current version
     */
    fun getVersionIconFile(version: Version) = File(getTurtleVersionPath(version), "VersionIcon.png")

    /**
     * @return the icon configured for a version name
     */
    fun getVersionIconFile(name: String) = File(getTurtleVersionPath(name), "VersionIcon.png")

    /**
     * @return the folder path of a version name
     */
    fun getVersionPath(name: String) = File(ProfilePathHome.getVersionsHome(), name)

    /**
     * Save the currently selected version.
     */
    fun saveCurrentVersion(versionName: String) {
        runCatching {
            currentGameInfo.apply {
                version = versionName
                saveCurrentInfo()
            }
        }.onFailure { e -> Logging.e("Save Current Version", Tools.printToString(e)) }
    }

    private fun validateVersionName(
        context: Context,
        newName: String,
        versionInfo: VersionInfo?
    ): String? {
        return when {
            isVersionExists(newName, true) ->
                context.getString(R.string.version_install_exists)
            versionInfo?.loaderInfo?.takeIf { it.isNotEmpty() }?.let {
                // Versions with ModLoader info may not be renamed to the vanilla name, to avoid clashes.
                newName == versionInfo.minecraftVersion
            } ?: false ->
                context.getString(R.string.version_install_cannot_use_mc_name)
            else -> null
        }
    }

    /**
     * Open the rename dialog; must run on the UI thread.
     * @param beforeRename hook running one step before the rename
     */
    fun openRenameDialog(context: Context, version: Version, beforeRename: (() -> Unit)? = null) {
        EditTextDialog.Builder(context)
            .setTitle(R.string.version_manager_rename)
            .setEditText(version.getVersionName())
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                val string = editText.text.toString()

                // Same as the original name.
                if (string == version.getVersionName()) return@setConfirmListener true

                if (FileTools.isFilenameInvalid(editText)) {
                    return@setConfirmListener false
                }

                val error = validateVersionName(context, string, version.getVersionInfo())
                error?.let {
                    editText.error = it
                    return@setConfirmListener false
                }

                beforeRename?.invoke()
                renameVersion(version, string)

                true
            }.showDialog()
    }

    /**
     * Rename the current version; the new name is not validated here.
     */
    private fun renameVersion(version: Version, name: String) {
        val currentVersionName = getCurrentVersion()?.getVersionName()
        // If the current version is the one being renamed, apply the new name to it as well.
        if (version.getVersionName() == currentVersionName) saveCurrentVersion(name)

        // Try to refresh the version names inside the favorite groups.
        FavoritesVersionUtils.renameVersion(version.getVersionName(), name)

        val versionFolder = version.getVersionPath()
        val renameFolder = File(ProfilePathHome.getVersionsHome(), name)

        // Whatever the folder is called after the rename, if it exists it must be deleted.
        // otherwise things break.
        FileUtils.deleteQuietly(renameFolder)

        val originalName = versionFolder.name

        FileTools.renameFile(versionFolder, renameFolder)

        val versionJsonFile = File(renameFolder, "$originalName.json")
        val versionJarFile = File(renameFolder, "$originalName.jar")
        val renameJsonFile = File(renameFolder, "$name.json")
        val renameJarFile = File(renameFolder, "$name.jar")

        FileTools.renameFile(versionJsonFile, renameJsonFile)
        FileTools.renameFile(versionJarFile, renameJarFile)

        FileUtils.deleteQuietly(versionFolder)

        // Refresh the list after the rename.
        refresh("VersionsManager:renameVersion")
    }

    /**
     * Open the name input for copying the selected version into a new one.
     */
    fun openCopyDialog(context: Context, version: Version) {
        val dialog = ZHTools.createTaskRunningDialog(context)
        EditTextDialog.Builder(context)
            .setTitle(R.string.version_manager_copy)
            .setMessage(R.string.version_manager_copy_tip)
            .setCheckBoxText(R.string.version_manager_copy_all)
            .setShowCheckBox(true)
            .setEditText(version.getVersionName())
            .setAsRequired()
            .setConfirmListener { editText, checked ->
                val string = editText.text.toString()

                // Same as the original name.
                if (string == version.getVersionName()) return@setConfirmListener true

                if (FileTools.isFilenameInvalid(editText)) {
                    return@setConfirmListener false
                }

                val error = validateVersionName(context, string, version.getVersionInfo())
                error?.let {
                    editText.error = it
                    return@setConfirmListener false
                }

                Task.runTask {
                    copyVersion(version, string, checked)
                }.beforeStart(TaskExecutors.getAndroidUI()) {
                    dialog.show()
                }.onThrowable { e ->
                    Tools.showErrorRemote(e)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                    refresh("VersionsManager:openCopyDialog")
                }.execute()
                true
            }.showDialog()
    }

    /**
     * Copy the selected version into a new one.
     * @param version the selected version
     * @param name the new version name
     * @param copyAllFile whether every file should be copied
     */
    private fun copyVersion(version: Version, name: String, copyAllFile: Boolean) {
        val versionsFolder = version.getVersionsFolder()
        val newVersion = File(versionsFolder, name)

        val originalName = version.getVersionName()

        // json and jar files of the new version.
        val newJsonFile = File(newVersion, "$name.json")
        val newJarFile = File(newVersion, "$name.jar")

        val originalVersionFolder = version.getVersionPath()
        if (copyAllFile) {
            // With full copying enabled, clone the whole original folder into the new version.
            FileUtils.copyDirectory(originalVersionFolder, newVersion)
            // Rename the json/jar files.
            val jsonFile = File(newVersion, "$originalName.json")
            val jarFile = File(newVersion, "$originalName.jar")
            if (jsonFile.exists()) jsonFile.renameTo(newJsonFile)
            if (jarFile.exists()) jarFile.renameTo(newJarFile)
        } else {
            // When not copying everything, only copy and rename the json/jar files.
            val originalJsonFile = File(originalVersionFolder, "$originalName.json")
            val originalJarFile = File(originalVersionFolder, "$originalName.jar")
            newVersion.mkdirs()
            // versions/1.21.3/1.21.3.json -> versions/name/name.json
            if (originalJsonFile.exists()) originalJsonFile.copyTo(newJsonFile)
            // versions/1.21.3/1.21.3.jar -> versions/name/name.jar
            if (originalJarFile.exists()) originalJarFile.copyTo(newJarFile)
        }

        // Save the version config file.
        version.getVersionConfig().copy().let { config ->
            config.setVersionPath(newVersion)
            config.setIsolationType(VersionConfig.IsolationType.ENABLE)
            config.saveWithThrowable()
        }
    }

    private fun getVersion(name: String?): Version? {
        name?.let { versionName ->
            return versions.find { it.getVersionName() == versionName }?.takeIf { it.isValid() }
        }
        return null
    }
}