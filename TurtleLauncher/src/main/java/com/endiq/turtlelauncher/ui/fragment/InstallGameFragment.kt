package com.endiq.turtlelauncher.ui.fragment

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentInstallGameBinding
import com.endiq.turtlelauncher.event.sticky.SelectInstallTaskEvent
import com.endiq.turtlelauncher.event.value.InstallGameEvent
import com.endiq.turtlelauncher.utils.LauncherProfiles
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.feature.version.install.ExtraModInstall
import com.endiq.turtlelauncher.feature.version.install.InstallArgsUtils
import com.endiq.turtlelauncher.feature.mod.modloader.TurtleClientDownloadTask
import com.endiq.turtlelauncher.feature.version.install.InstallTask
import com.endiq.turtlelauncher.feature.version.install.InstallTaskItem
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.dialog.InstallExtrasDialog
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadFabricApiFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadFabricFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadForgeFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadCleanroomFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadNeoForgeFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadOptiFineFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadQuiltApiFragment
import com.endiq.turtlelauncher.ui.fragment.download.addon.DownloadQuiltFragment
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.runtime.SelectRuntimeUtils
import net.endiq.launcher.JavaGUILauncherActivity
import net.endiq.launcher.Tools
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.EnumMap


class InstallGameFragment : FragmentWithAnim(R.layout.fragment_install_game), View.OnClickListener {
    companion object {
        const val TAG = "InstallGameFragment"
        const val BUNDLE_MC_VERSION = "bundle_mc_version"
        const val FEATURED_MODPACK_QUERY = "Simply Optimized Reloaded"
    }
    private lateinit var binding: FragmentInstallGameBinding
    private lateinit var mcVersion: String
    private val addonMap: MutableMap<Addon, Pair<String, InstallTask>> = EnumMap(Addon::class.java)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentInstallGameBinding.inflate(layoutInflater)

        EventBus.getDefault().getStickyEvent(SelectInstallTaskEvent::class.java)?.let { event ->
            addonMap[event.addon] = Pair(event.selectedVersion, event.task)
            EventBus.getDefault().removeStickyEvent(event)
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mcVersion = arguments?.getString(BUNDLE_MC_VERSION) ?: throw IllegalArgumentException("The Minecraft version is not passed")

        binding.apply {
            nameEdit.setText(mcVersion)

            val clickListener = this@InstallGameFragment
            optifineLayout.setOnClickListener(clickListener)
            optifineDelete.setOnClickListener(clickListener)
            forgeLayout.setOnClickListener(clickListener)
            forgeDelete.setOnClickListener(clickListener)
            neoforgeLayout.setOnClickListener(clickListener)
            neoforgeDelete.setOnClickListener(clickListener)
            fabricLayout.setOnClickListener(clickListener)
            fabricDelete.setOnClickListener(clickListener)
            fabricApiLayout.setOnClickListener(clickListener)
            fabricApiDelete.setOnClickListener(clickListener)
            quiltLayout.setOnClickListener(clickListener)
            quiltDelete.setOnClickListener(clickListener)
            quiltApiLayout.setOnClickListener(clickListener)
            quiltApiDelete.setOnClickListener(clickListener)
            cleanroomLayout.setOnClickListener(clickListener)
            cleanroomDelete.setOnClickListener(clickListener)
            modpackLayout.setOnClickListener(clickListener)

            back.setOnClickListener(clickListener)
            installFab.setOnClickListener(clickListener)
        }
    }

    override fun onResume() {
        super.onResume()
        checkIncompatible()
    }

    /**
     * Check for incompatible addons and block selecting that addon version.
     */
    @SuppressLint("SetTextI18n")
    private fun checkIncompatible() {
        binding.apply {
            checkIncompatible(Addon.OPTIFINE, optifineLayout, optifineVersion, optifineInstall, optifineDelete, addonMap.size > 1)
            checkIncompatible(Addon.FORGE, forgeLayout, forgeVersion, forgeInstall, forgeDelete)
            checkIncompatible(Addon.NEOFORGE, neoforgeLayout, neoforgeVersion, neoforgeInstall, neoforgeDelete)
            checkIncompatible(Addon.FABRIC, fabricLayout, fabricVersion, fabricInstall, fabricDelete)
            checkIncompatible(Addon.FABRIC_API, fabricApiLayout, fabricApiVersion, fabricApiInstall, fabricApiDelete, true)
            checkIncompatible(Addon.QUILT, quiltLayout, quiltVersion, quiltInstall, quiltDelete)
            checkIncompatible(Addon.QSL, quiltApiLayout, quiltApiVersion, quiltApiInstall, quiltApiDelete, true)
            checkIncompatible(Addon.CLEANROOM, cleanroomLayout, cleanroomVersion, cleanroomInstall, cleanroomDelete)

            val loaderName = addonMap.keys
                .firstOrNull { it != Addon.OPTIFINE || addonMap.size == 1 }
                ?.addonName.orEmpty()

            nameEdit.setText("$mcVersion $loaderName".trim())
        }
    }

    /**
     * Check whether the given addon has a known conflict in the AddonMap.
     * @param addon the Addon passed in
     * @param layout layout of the Addon
     * @param versionText version info of the Addon
     * @param installText install type of the Addon
     */
    private fun checkIncompatible(
        addon: Addon,
        layout: View,
        versionText: TextView,
        installText: TextView,
        imageView: ImageView,
        modInstallType: Boolean = false
    ) {
        val incompatible: MutableSet<Addon> = HashSet()
        addonMap.keys.forEach { selectedAddon ->
            if (Addon.getCompatibles(addon)?.contains(selectedAddon) == false) {
                incompatible.add(selectedAddon)
            }
        }

        if (incompatible.isNotEmpty()) {
            layout.isEnabled = false
            installText.text = getString(R.string.version_install_incompatible, incompatible.joinToString(", ", transform = { it.addonName }))
            versionText.visibility = View.GONE
            imageView.visibility = View.GONE
        } else {
            layout.isEnabled = true
            imageView.visibility = View.VISIBLE
            val version = addonMap[addon]?.first

            if (version != null) {
                versionText.visibility = View.VISIBLE
                versionText.text = version
                installText.setText(if (modInstallType) R.string.version_install_type_mod else R.string.version_install_type_version)
            } else {
                versionText.visibility = View.GONE
                installText.setText(R.string.version_install_not_install)
            }
        }

        val contains = addonMap.containsKey(addon)
        layout.isSelected = contains
        if (contains) {
            imageView.isEnabled = true
            imageView.setImageResource(R.drawable.ic_close)
        } else {
            imageView.isEnabled = false
            imageView.setImageResource(R.drawable.ic_spinner_arrow_right)
        }
    }

    /**
     * Switch to the Addon version picker screen.
     */
    private fun swapFragment(fragmentClass: Class<out Fragment>, tag: String) {
        val bundle = Bundle()
        bundle.putString(BUNDLE_MC_VERSION, mcVersion)
        ZHTools.swapFragmentWithAnim(this, fragmentClass, tag, bundle)
    }

    /**
     * Remove an addon and refresh the current incompatibilities.
     */
    private fun removeAddon(addon: Addon) {
        addonMap.remove(addon)
        checkIncompatible()
    }

    private fun showInstallExtrasDialog(activity: FragmentActivity, customVersionName: String) {
        InstallExtrasDialog(activity) { includeTurtleClient, includeFpsBoost ->
            proceedWithInstall(activity, customVersionName, includeTurtleClient, includeFpsBoost)
        }.show()
    }

    private fun proceedWithInstall(
        activity: FragmentActivity,
        customVersionName: String,
        includeTurtleClient: Boolean,
        includeFpsBoost: Boolean
    ) {
        fun install() {
            EventBus.getDefault().post(
                InstallGameEvent(
                    mcVersion,
                    customVersionName,
                    organizeInstallationTasks(customVersionName, includeTurtleClient, includeFpsBoost)
                )
            )
            Tools.backToMainMenu(activity)
        }

        // Check whether OptiFine and Forge addons are present at the same time.
        // Tell the user about the compatibility problem at the end.
        if (addonMap.containsKey(Addon.OPTIFINE) && addonMap.containsKey(Addon.FORGE)) {
            TipDialog.Builder(activity)
                .setTitle(R.string.generic_warning)
                .setMessage(R.string.version_install_optifine_and_forge)
                .setWarning()
                .setConfirmClickListener { install() }
                .showDialog()
        } else install()
    }

    override fun onClick(v: View) {
        val activity = requireActivity()

        binding.apply {
            when (v) {
                optifineLayout -> swapFragment(DownloadOptiFineFragment::class.java, DownloadOptiFineFragment.TAG)
                forgeLayout -> swapFragment(DownloadForgeFragment::class.java, DownloadForgeFragment.TAG)
                neoforgeLayout -> swapFragment(DownloadNeoForgeFragment::class.java, DownloadNeoForgeFragment.TAG)
                fabricLayout -> swapFragment(DownloadFabricFragment::class.java, DownloadFabricFragment.TAG)
                fabricApiLayout -> swapFragment(DownloadFabricApiFragment::class.java, DownloadFabricApiFragment.TAG)
                quiltLayout -> swapFragment(DownloadQuiltFragment::class.java, DownloadQuiltFragment.TAG)
                quiltApiLayout -> swapFragment(DownloadQuiltApiFragment::class.java, DownloadQuiltApiFragment.TAG)
                cleanroomLayout -> swapFragment(DownloadCleanroomFragment::class.java, DownloadCleanroomFragment.TAG)
                modpackLayout -> {
                    val bundle = android.os.Bundle().apply {
                        putInt(com.endiq.turtlelauncher.ui.fragment.DownloadFragment.ARG_INITIAL_TAB, 1) // ModPack tab
                        putString(com.endiq.turtlelauncher.ui.fragment.DownloadFragment.ARG_INITIAL_QUERY, FEATURED_MODPACK_QUERY)
                    }
                    ZHTools.swapFragmentWithAnim(this@InstallGameFragment, com.endiq.turtlelauncher.ui.fragment.DownloadFragment::class.java,
                        com.endiq.turtlelauncher.ui.fragment.DownloadFragment.TAG, bundle)
                }

                optifineDelete -> removeAddon(Addon.OPTIFINE)
                forgeDelete -> removeAddon(Addon.FORGE)
                neoforgeDelete -> removeAddon(Addon.NEOFORGE)
                fabricDelete -> removeAddon(Addon.FABRIC)
                fabricApiDelete -> removeAddon(Addon.FABRIC_API)
                quiltDelete -> removeAddon(Addon.QUILT)
                quiltApiDelete -> removeAddon(Addon.QSL)
                cleanroomDelete -> removeAddon(Addon.CLEANROOM)

                installFab -> {
                    val string = nameEdit.text?.toString()
                    if (string.isNullOrBlank()) {
                        nameEdit.error = getString(R.string.generic_error_field_empty)
                        return
                    }

                    if (FileTools.isFilenameInvalid(nameEdit)) {
                        return
                    }

                    if (VersionsManager.isVersionExists(string, true)) {
                        nameEdit.error = getString(R.string.version_install_exists)
                        return
                    }

                    if (addonMap.isNotEmpty() && string.equals(mcVersion, true)) {
                        nameEdit.error = getString(R.string.version_install_cannot_use_mc_name)
                        return
                    }

                    showInstallExtrasDialog(activity, string)
                }
                back -> ZHTools.onBackPressed(activity)
                else -> {}
            }
        }
    }

    private fun organizeInstallationTasks(
        customVersionName: String,
        includeTurtleClient: Boolean = false,
        includeFpsBoost: Boolean = false
    ): Map<Addon, InstallTaskItem> {
        val mapSize = addonMap.size
        val taskMap: MutableMap<Addon, InstallTaskItem> = EnumMap(Addon::class.java)

        fun getModPath(): File {
            return if (AllSettings.versionIsolation.getValue()) // version isolation enabled
                File(
                    ProfilePathHome.getGameHome(),
                    "versions${File.separator}$customVersionName${File.separator}mods"
                )
            else File(ProfilePathHome.getGameHome(), "mods")
        }

        addonMap.forEach { (addon, taskPair) ->
            when (addon) {
                Addon.OPTIFINE -> {
                    val endTask: InstallTaskItem.EndTask = if (mapSize < 2) { // install as a single version
                        InstallTaskItem.EndTask { activity, file ->
                            installInGUITask(activity, addon.addonName, taskPair.first) { intent, argUtils ->
                                argUtils.setOptiFine(intent, file, customVersionName)
                            }
                        }
                    } else {
                        InstallTaskItem.EndTask { _, file ->
                            moveFile(file, File(getModPath(), "${taskPair.first}.jar"))
                        }
                    }
                    taskMap[addon] = InstallTaskItem(taskPair.first, mapSize > 1, taskPair.second, endTask)
                }
                Addon.FORGE -> {
                    taskMap[addon] = InstallTaskItem(taskPair.first, false, taskPair.second) {  activity, file ->
                        installInGUITask(activity, addon.addonName, taskPair.first) { intent, argUtils ->
                            argUtils.setForge(intent, file, customVersionName)
                        }
                    }
                }
                Addon.NEOFORGE -> {
                    taskMap[addon] = InstallTaskItem(taskPair.first, false, taskPair.second) {  activity, file ->
                        installInGUITask(activity, addon.addonName, taskPair.first) { intent, argUtils ->
                            argUtils.setNeoForge(intent, file, customVersionName)
                        }
                    }
                }
                Addon.FABRIC -> {
                    taskMap[addon] = InstallTaskItem(taskPair.first, false, taskPair.second) {  activity, file ->
                        installInGUITask(activity, addon.addonName, taskPair.first) { intent, argUtils ->
                            argUtils.setFabric(intent, file, customVersionName)
                        }
                    }
                }
                Addon.FABRIC_API -> taskMap[addon] = InstallTaskItem(taskPair.first, true, taskPair.second) { _, file ->
                    moveFile(file, File(getModPath(), "${taskPair.first}.jar"))
                }
                Addon.QUILT -> taskMap[addon] = InstallTaskItem(taskPair.first, false, taskPair.second, null)
                Addon.QSL -> taskMap[addon] = InstallTaskItem(taskPair.first, true, taskPair.second) { _, file ->
                    moveFile(file, File(getModPath(), "${taskPair.first}.jar"))
                }
                Addon.CLEANROOM -> {
                    taskMap[addon] = InstallTaskItem(taskPair.first, false, taskPair.second) { activity, file ->
                        installInGUITask(activity, addon.addonName, taskPair.first) { intent, argUtils ->
                            argUtils.setForge(intent, file, customVersionName)
                        }
                    }
                }
                Addon.TURTLE_CLIENT -> {}
            }
        }
        if (includeTurtleClient) {
            taskMap[Addon.TURTLE_CLIENT] = InstallTaskItem(
                mcVersion,
                true,
                TurtleClientDownloadTask(mcVersion)
            ) { _, file ->
                moveFile(file, File(getModPath(), file.name))
            }
        }
        // FPS Boost still has no real mod resource assigned yet - see ExtraModInstall.
        if (includeFpsBoost) ExtraModInstall.logPending(ExtraModInstall.FPS_BOOST)

        return taskMap
    }

    @Throws(Throwable::class)
    private fun moveFile(file: File, file1: File) {
        if (file1.exists()) FileUtils.deleteQuietly(file1)
        FileUtils.moveFile(file, file1)
    }

    /**
     * Installed from inside the Java GUI; as an EndTask it must run on the UI thread.
     * @param activity **the Activity context is mandatory here! Never pass the Fragment context:
     *                by the time this runs the Fragment is long gone!**
     */
    @Throws(Throwable::class)
    private fun installInGUITask(activity: Activity, addonName: String, selectVersion: String, setArgs: (Intent, InstallArgsUtils) -> Unit) {
        val intent = Intent(activity, JavaGUILauncherActivity::class.java)

        val argUtils = InstallArgsUtils(mcVersion, selectVersion)
        setArgs(intent, argUtils)

        SelectRuntimeUtils.selectRuntime(activity, activity.getString(R.string.version_install_new_modloader, addonName)) { jreName ->
            LauncherProfiles.generateLauncherProfiles()
            intent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName)
            activity.startActivity(intent)
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.nameLayout, TurtleTransitions.enter()))
            .apply(AnimPlayer.Entry(binding.addonsLayout, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.nameLayout, TurtleTransitions.exit()))
            .apply(AnimPlayer.Entry(binding.addonsLayout, TurtleTransitions.exit()))
    }
}