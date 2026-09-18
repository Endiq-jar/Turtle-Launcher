package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentJavaBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.EditTextSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.formatFileSize
import com.endiq.turtlelauncher.utils.platform.MemoryUtils.Companion.getFreeDeviceMemory
import com.endiq.turtlelauncher.utils.platform.MemoryUtils.Companion.getTotalDeviceMemory
import com.endiq.turtlelauncher.utils.platform.MemoryUtils.Companion.getUsedDeviceMemory
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.endiq.launcher.Architecture
import net.endiq.launcher.Tools
import net.endiq.launcher.contracts.OpenDocumentWithExtension
import net.endiq.launcher.multirt.MultiRTConfigDialog
import kotlin.math.min


class JavaSettingsFragment : AbstractSettingsFragment(R.layout.settings_fragment_java, SettingCategory.JAVA) {
    companion object {
        const val TAG: String = "JavaSettingsFragment"
    }

    private lateinit var binding: SettingsFragmentJavaBinding
    private val mVmInstallLauncher = registerForActivityResult(
        OpenDocumentWithExtension("xz")
    ) { uris: List<Uri>? ->
        uris?.let { uriList ->
            uriList[0].let { data ->
                Tools.installRuntimeFromUri(context, data)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentJavaBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        BaseSettingsWrapper(
            context,
            binding.installJreLayout
        ) {
            MultiRTConfigDialog().apply {
                prepare(context, mVmInstallLauncher)
            }.show()
        }

        ListSettingsWrapper(
            context,
            AllSettings.selectRuntimeMode,
            binding.selectRuntimeModeLayout,
            binding.selectRuntimeModeTitle,
            binding.selectRuntimeModeValue,
            R.array.select_java_runtime_names, R.array.select_java_runtime_values
        )

        EditTextSettingsWrapper(
            AllSettings.javaArgs,
            binding.javaArgsLayout,
            binding.javaArgsEdittext
        )

        val deviceRam = Tools.getTotalDeviceMemory(context)
        val maxRAM = if (Architecture.is32BitsDevice() || deviceRam < 2048) min(
            1024.0,
            deviceRam.toDouble()
        ).toInt()
        else deviceRam - (if (deviceRam < 3064) 800 else 1024) //To have a minimum for the device to breathe

        SeekBarSettingsWrapper(
            context,
            AllSettings.ramAllocation.value,
            binding.allocationLayout,
            binding.allocationTitle,
            binding.allocationSummary,
            binding.allocationValue,
            binding.allocation,
            "MB"
        ) { wrapper ->
            wrapper.seekbarView.max = maxRAM
            wrapper.seekbarView.progress = AllSettings.ramAllocation.value.getValue()
            wrapper.setSeekBarValueTextView()

            updateMemoryInfo(context, wrapper.seekbarView.progress.toLong())
        }.apply {
            setOnSeekBarProgressChangeListener {
                updateMemoryInfo(
                    requireContext(),
                    seekbarView.progress.toLong()
                )
            }
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.javaSandbox,
            binding.javaSandboxLayout,
            binding.javaSandbox
        )
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    private fun updateMemoryInfo(context: Context, seekValue: Long) {
        val value = seekValue * 1024 * 1024
        val freeDeviceMemory = getFreeDeviceMemory(context)

        val isMemorySizeExceeded = value > freeDeviceMemory

        var summary = getMemoryInfoText(context, freeDeviceMemory)
        if (isMemorySizeExceeded) summary =
            StringUtils.insertNewline(summary, getString(R.string.setting_java_memory_exceeded))

        TaskExecutors.runInUIThread { binding.allocationMemory.text = summary }
    }

    private fun getMemoryInfoText(context: Context, freeDeviceMemory: Long): String {
        return getString(
            R.string.setting_java_memory_info,
            formatFileSize(getUsedDeviceMemory(context)),
            formatFileSize(getTotalDeviceMemory(context)),
            formatFileSize(freeDeviceMemory)
        )
    }
}
