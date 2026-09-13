package com.endiq.turtlelauncher.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentLauncherBinding
import com.endiq.turtlelauncher.event.single.PageOpacityChangeEvent
import com.endiq.turtlelauncher.feature.update.UpdateUtils
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.CustomBackgroundFragment
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.CleanUpCache.Companion.start
import com.endiq.turtlelauncher.utils.ZHTools
import net.kdt.pojavlaunch.LauncherActivity
import org.greenrobot.eventbus.EventBus
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions


class LauncherSettingsFragment() : AbstractSettingsFragment(R.layout.settings_fragment_launcher, SettingCategory.LAUNCHER) {
    companion object {
        const val TAG: String = "LauncherSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentLauncherBinding
    private var parentFragment: FragmentWithAnim? = null

    constructor(parentFragment: FragmentWithAnim?) : this() {
        this.parentFragment = parentFragment
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentLauncherBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { com.endiq.turtlelauncher.utils.ZHTools.onBackPressed(requireActivity()) }

        SwitchSettingsWrapper(
            context,
            AllSettings.checkLibraries,
            binding.checkLibrariesLayout,
            binding.checkLibraries
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.verifyManifest,
            binding.verifyManifestLayout,
            binding.verifyManifest
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.resourceImageCache,
            binding.resourceImageCacheLayout,
            binding.resourceImageCache
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.addFullResourceName,
            binding.addFullResourceNameLayout,
            binding.addFullResourceName
        )

        // TurtleLauncher: built-in AI Assistant (top bar -> Assistant). Runs fully on-device -
        // no API key, no account, no network (see feature/ai/TurtleAssistant.kt) - so these
        // two switches are about visibility and local storage only. The optional AI crash
        // help / skin filter live in Experimental and are the ones that need a key.
        SwitchSettingsWrapper(
            context,
            AllSettings.aiAssistantEnabled,
            binding.aiAssistantEnabledLayout,
            binding.aiAssistantEnabled
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.aiAssistantHistoryEnabled,
            binding.aiAssistantHistoryEnabledLayout,
            binding.aiAssistantHistoryEnabled
        )

        ListSettingsWrapper(
            context,
            AllSettings.downloadSource,
            binding.downloadSourceLayout,
            binding.downloadSourceTitle,
            binding.downloadSourceValue,
            R.array.download_source_names, R.array.download_source_values
        )

        ListSettingsWrapper(
            context,
            AllSettings.dnsServer,
            binding.dnsServerLayout,
            binding.dnsServerTitle,
            binding.dnsServerValue,
            R.array.dns_server_names, R.array.dns_server_values
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.maxDownloadThreads,
            binding.maxDownloadThreadsLayout,
            binding.maxDownloadThreadsTitle,
            binding.maxDownloadThreadsSummary,
            binding.maxDownloadThreadsValue,
            binding.maxDownloadThreads,
            ""
        )

        ListSettingsWrapper(
            context,
            AllSettings.launcherTheme,
            binding.launcherThemeLayout,
            binding.launcherThemeTitle,
            binding.launcherThemeValue,
            R.array.launcher_theme_names, R.array.launcher_theme_values
        ).setRequiresReboot()

        BaseSettingsWrapper(
            context,
            binding.customBackgroundLayout
        ) {
            ZHTools.swapFragmentWithAnim(
                    this,
                    CustomBackgroundFragment::class.java,
                    CustomBackgroundFragment.TAG,
                    null
                )
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.animation,
            binding.animationLayout,
            binding.animation
        )

        // TurtleLauncher: the transition pickers. These feed TurtleTransitions, which every
        // screen transition and every show/hide asks for its animation - so these two lists
        // are what actually make slide/bounce/fade/zoom reachable app-wide.
        ListSettingsWrapper(
            context,
            AllSettings.animationEnter,
            binding.animationEnterLayout,
            binding.animationEnterTitle,
            binding.animationEnterValue,
            R.array.animation_enter_names, R.array.animation_enter_values
        )

        ListSettingsWrapper(
            context,
            AllSettings.animationExit,
            binding.animationExitLayout,
            binding.animationExitTitle,
            binding.animationExitValue,
            R.array.animation_exit_names, R.array.animation_exit_values
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.animationSpeed,
            binding.animationSpeedLayout,
            binding.animationSpeedTitle,
            binding.animationSpeedSummary,
            binding.animationSpeedValue,
            binding.animationSpeed,
            "ms"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.pageOpacity,
            binding.pageOpacityLayout,
            binding.pageOpacityTitle,
            binding.pageOpacitySummary,
            binding.pageOpacityValue,
            binding.pageOpacity,
            "%"
        ).setOnSeekBarProgressChangeListener {
            EventBus.getDefault().post(PageOpacityChangeEvent(it))
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.enableLogOutput,
            binding.enableLogOutputLayout,
            binding.enableLogOutput
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.quitLauncher,
            binding.quitLauncherLayout,
            binding.quitLauncher
        )

        BaseSettingsWrapper(
            context,
            binding.cleanUpCacheLayout
        ) {
            start(context)
        }

        BaseSettingsWrapper(
            context,
            binding.checkUpdateLayout
        ) {
            UpdateUtils.checkDownloadedPackage(context, force = true, ignore = false)
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.acceptPreReleaseUpdates,
            binding.acceptPreReleaseUpdatesLayout,
            binding.acceptPreReleaseUpdates
        )

        val notificationPermissionRequest = SwitchSettingsWrapper(
            context,
            AllSettings.notificationPermissionRequest,
            binding.notificationPermissionRequestLayout,
            binding.notificationPermissionRequest
        )
        setupNotificationRequestPreference(notificationPermissionRequest)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    private fun setupNotificationRequestPreference(notificationPermissionRequest: SwitchSettingsWrapper) {
        val activity = requireActivity()
        if (activity is LauncherActivity) {
            if (ZHTools.checkForNotificationPermission()) notificationPermissionRequest.setGone()
            notificationPermissionRequest.switchView.setOnCheckedChangeListener { _, _ ->
                activity.askForNotificationPermission {
                    notificationPermissionRequest.mainView.visibility = View.GONE
                }
            }
        } else {
            notificationPermissionRequest.mainView.visibility = View.GONE
        }
    }
}