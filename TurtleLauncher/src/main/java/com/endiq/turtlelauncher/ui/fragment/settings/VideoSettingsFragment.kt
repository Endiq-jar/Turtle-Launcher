package com.endiq.turtlelauncher.ui.fragment.settings

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentVideoBinding
import com.endiq.turtlelauncher.event.single.LauncherIgnoreNotchEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.plugins.driver.DriverPluginManager
import com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.renderer.renderers.HolyGL4ESRenderer
import com.endiq.turtlelauncher.renderer.renderers.MobileGluesRenderer
import com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.setting.AllStaticSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.LocalRendererPluginDialog
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.endiq.turtlelauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.path.UrlManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import java.io.File


class VideoSettingsFragment : AbstractSettingsFragment(R.layout.settings_fragment_video, SettingCategory.VIDEO) {
    private lateinit var binding: SettingsFragmentVideoBinding
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Any>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult(OpenDocumentWithExtension("zip", true)) { uris: List<Uri>? ->
            uris?.let { uriList ->
                val dialog = ZHTools.showTaskRunningDialog(requireActivity())
                Task.runTask {
                    val pluginFiles = mutableListOf<File>()
                    uriList.forEach { uri ->
                        val file = FileTools.copyFileInBackground(requireActivity(), uri, PathManager.DIR_CACHE.absolutePath)
                        pluginFiles.add(file)
                    }
                    pluginFiles.takeIf { it.isNotEmpty() }
                }.beforeStart(TaskExecutors.getAndroidUI()) {
                    dialog.show()
                }.ended { pluginFiles ->
                    pluginFiles?.let { files ->
                        var requiresRestart = false
                        files.forEach { pluginFile ->
                            val info = if (RendererPluginManager.importLocalRendererPlugin(pluginFile)) {
                                requiresRestart = true
                                "The renderer plugin has been successfully imported!"
                            } else {
                                "The renderer plugin import failed!"
                            }
                            Logging.i("VideoSettings", info)
                            FileUtils.deleteQuietly(pluginFile)
                        }
                        TaskExecutors.runInUIThread {
                            if (requiresRestart) {
                                TipDialog.Builder(requireActivity())
                                    .setTitle(R.string.generic_warning)
                                    .setMessage(R.string.setting_renderer_local_import_restart)
                                    .setWarning()
                                    .setConfirmClickListener { ZHTools.killProcess() }
                                    .showDialog()
                            } else {
                                TipDialog.Builder(requireActivity())
                                    .setTitle(R.string.generic_tip)
                                    .setMessage(R.string.setting_renderer_local_import_failed)
                                    .showDialog()
                            }
                        }
                    }
                }.onThrowable { e ->
                    Tools.showErrorRemote(e)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                }.execute()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentVideoBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireActivity()
        binding.subSettingsBackButton.setOnClickListener { com.endiq.turtlelauncher.utils.ZHTools.onBackPressed(requireActivity()) }

        // ── Renderer & Driver ─────────────────────────────────────────────────
        val compatibleRenderers = Renderers.getCompatibleRenderers(context)
        val renderers = compatibleRenderers.first
        val rendererInstances = compatibleRenderers.second
        ListSettingsWrapper(
            context, AllSettings.renderer,
            binding.rendererLayout, binding.rendererTitle, binding.rendererValue,
            renderers.rendererNames.toTypedArray(),
            renderers.rendererIdentifier.toTypedArray()
        )
        binding.rendererDownload.setOnClickListener { ZHTools.openLink(context, UrlManager.URL_FCL_RENDERER_PLUGIN) }
        binding.rendererManagerButton.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, com.endiq.turtlelauncher.ui.fragment.settings.RendererManagerFragment::class.java, com.endiq.turtlelauncher.ui.fragment.settings.RendererManagerFragment.TAG, null)
        }

        // ── Graphics Backend switch (Vulkan / OpenGL) ────────────────────────────
        // Simple shortcut over the same AllSettings.renderer used above: OpenGL maps to the
        // launcher's default GL-family renderer, Vulkan maps to ZinkRenderer's UUID (Zink
        // translates Minecraft's GL calls to Vulkan). Matched by renderer id, not display
        // name, so this doesn't break if a renderer's display name ever changes. Falls back
        // to whichever of the two is actually compatible/present in the device's renderer
        // list, in case one isn't available.
        //
        // TurtleLauncher: the OpenGL side used to hardcode Holy GL4ES. Now that MobileGlues
        // is this launcher's default renderer (see AllSettings.renderer), flipping this
        // switch back to "OpenGL" should land on the same renderer a fresh install gets, not
        // on a different one - so it prefers MobileGlues and only falls back to Holy GL4ES
        // on a device where MobileGlues isn't in the compatible list at all (Renderers
        // already filters those out by bundled library, see Renderers.hasRequiredLibrary).
        val openglRendererId = rendererInstances.find { it.getRendererId() == MobileGluesRenderer.ID }?.getUniqueIdentifier()
            ?: rendererInstances.find { it.getRendererId() == HolyGL4ESRenderer.ID }?.getUniqueIdentifier()
        val vulkanRendererId = rendererInstances.find { it.getRendererId() == ZinkRenderer.ID }?.getUniqueIdentifier()
        fun applyBackend(rendererId: String?) {
            if (rendererId == null) return
            AllSettings.renderer.put(rendererId).save()
            val index = renderers.rendererIdentifier.indexOf(rendererId)
            if (index >= 0) binding.rendererValue.text = renderers.rendererNames[index]
        }
        // Re-sync the switch's persisted state to the actual active renderer every time
        // this screen opens, so it can never silently disagree with the detailed Renderer
        // option above - e.g. if the user picked a third renderer directly from that
        // dropdown instead of using this shortcut. Only Vulkan (Zink) reads as "off";
        // anything else (including a renderer neither shortcut maps to) reads as "on" /
        // OpenGL, matching the requested default of On = OpenGL.
        val currentRendererId = AllSettings.renderer.getValue()
        AllSettings.preferOpenGLBackend.put(currentRendererId != vulkanRendererId).save()
        SwitchSettingsWrapper(context, AllSettings.preferOpenGLBackend, binding.graphicsBackendLayout, binding.graphicsBackendSwitch)
            .setOnCheckedChangeListener { _, isChecked, listener ->
                listener.onSave()
                applyBackend(if (isChecked) openglRendererId else vulkanRendererId)
            }
        if (vulkanRendererId == null) {
            // Device has no Vulkan support at all - disable the switch rather than letting
            // the user toggle to a backend that isn't actually available.
            binding.graphicsBackendSwitch.isEnabled = false
            binding.graphicsBackendLayout.alpha = 0.5f
        }

        BaseSettingsWrapper(context, binding.rendererLocalImportLayout) {
            openDocumentLauncher.launch("zip")
        }
        binding.rendererLocalImportManage.setOnClickListener {
            if (RendererPluginManager.getAllLocalRendererList().isNotEmpty()) {
                LocalRendererPluginDialog(requireActivity()).show()
            }
        }

        val driverNames = DriverPluginManager.getDriverNameList().toTypedArray()
        ListSettingsWrapper(
            context, AllSettings.driver,
            binding.driverLayout, binding.driverTitle, binding.driverValue,
            driverNames, driverNames
        )
        binding.driverDownload.setOnClickListener { ZHTools.openLink(context, UrlManager.URL_FCL_DRIVER_PLUGIN) }

        // ── Notch ─────────────────────────────────────────────────────────────
        val ignoreNotch = SwitchSettingsWrapper(
            context, AllSettings.ignoreNotch,
            binding.ignoreNotchLayout, binding.ignoreNotch
        )
        val ignoreNotchLauncher = SwitchSettingsWrapper(
            context, AllSettings.ignoreNotchLauncher,
            binding.ignoreNotchLauncherLayout, binding.ignoreNotchLauncher
        ).setOnCheckedChangeListener { _, _, listener ->
            listener.onSave()
            EventBus.getDefault().post(LauncherIgnoreNotchEvent())
        }
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && AllStaticSettings.notchSize > 0)) {
            ignoreNotch.setGone()
            ignoreNotchLauncher.setGone()
        }

        // ── Resolution ────────────────────────────────────────────────────────
        SeekBarSettingsWrapper(
            context, AllSettings.resolutionRatio,
            binding.resolutionRatioLayout,
            binding.resolutionRatioTitle, binding.resolutionRatioSummary,
            binding.resolutionRatioValue, binding.resolutionRatio, "%"
        ).setOnSeekBarProgressChangeListener { progress ->
            changeResolutionRatioPreview(progress)
        }

        // TurtleLauncher v10: quick resolution presets + auto-detect
        fun applyResolutionPreset(percent: Int) {
            AllSettings.resolutionRatio.put(percent).save()
            binding.resolutionRatio.progress = percent
            changeResolutionRatioPreview(percent)
        }
        binding.resolutionPreset100.setOnClickListener { applyResolutionPreset(100) }
        binding.resolutionPreset75.setOnClickListener { applyResolutionPreset(75) }
        binding.resolutionPreset50.setOnClickListener { applyResolutionPreset(50) }
        binding.resolutionAutoDetectButton.setOnClickListener {
            com.endiq.turtlelauncher.launch.AutoSettingsOptimizer.apply(context)
            binding.resolutionRatio.progress = AllSettings.resolutionRatio.getValue()
            changeResolutionRatioPreview(AllSettings.resolutionRatio.getValue())
            Toast.makeText(context, getString(R.string.setting_resolution_auto_detect), Toast.LENGTH_SHORT).show()
        }

        // ── Video Switches ────────────────────────────────────────────────────
        SwitchSettingsWrapper(context, AllSettings.sustainedPerformance,
            binding.sustainedPerformanceLayout, binding.sustainedPerformance)

        SwitchSettingsWrapper(context, AllSettings.alternateSurface,
            binding.alternateSurfaceLayout, binding.alternateSurface)

        SwitchSettingsWrapper(context, AllSettings.forceVsync,
            binding.forceVsyncLayout, binding.forceVsync)

        SwitchSettingsWrapper(context, AllSettings.vsyncInZink,
            binding.vsyncInZinkLayout, binding.vsyncInZink)

        SwitchSettingsWrapper(context, AllSettings.adaptiveVsync,
            binding.adaptiveVsyncLayout, binding.adaptiveVsync)

        // TurtleLauncher bugfix (corrected): this genuinely is a distinct switch from the
        // "Low Latency Rendering" one under FPS Boost further down - it's the EGL front-buffer
        // setting (AllSettings.lowLatencyFrontBuffer), not the FPS Boost JVM one
        // (AllSettings.lowLatencyRendering). It was originally wired to
        // AllSettings.lowLatencyRendering (wrong - ambiguous/collided with the FPS Boost
        // setting) and to binding.lowLatencyRenderingLayout/binding.lowLatencyRendering,
        // which - because the XML ids these came from view-binding-converted to the exact
        // same field names as the FPS Boost switch's ids - pointed at that other switch
        // instead of its own. Renamed the XML ids (low_latency_rendering_* ->
        // low_latency_front_buffer_*) and repointed both refs here accordingly, rather than
        // deleting this block outright as an earlier pass through this bug mistakenly did.
        SwitchSettingsWrapper(context, AllSettings.lowLatencyFrontBuffer,
            binding.lowLatencyFrontBufferLayout, binding.lowLatencyFrontBuffer)

        val zinkPreferSystemDriver = SwitchSettingsWrapper(
            context, AllSettings.zinkPreferSystemDriver,
            binding.zinkPreferSystemDriverLayout, binding.zinkPreferSystemDriver
        )
        if (!Tools.checkVulkanSupport(context.packageManager)) {
            zinkPreferSystemDriver.setGone()
        } else {
            zinkPreferSystemDriver.setOnCheckedChangeListener { buttonView, isChecked, listener ->
                if (isChecked && ZHTools.isAdrenoGPU()) {
                    TipDialog.Builder(requireActivity())
                        .setTitle(R.string.generic_warning)
                        .setMessage(R.string.setting_zink_driver_adreno)
                        .setWarning()
                        .setCancelable(false)
                        .setConfirmClickListener { listener.onSave() }
                        .setCancelClickListener { buttonView.isChecked = false }
                        .showDialog()
                } else {
                    listener.onSave()
                }
            }
        }

        // ── Auto Settings Optimizer ──────────────────────────────────────────
        SwitchSettingsWrapper(context, AllSettings.autoSettingsOptimizer,
            binding.autoSettingsOptimizerLayout, binding.autoSettingsOptimizer)

        // FPS Boost / renderer performance toggles moved to OptimizationSettingsFragment.
        // gl4es LIBGL_* JNI tweaks moved to Phone Settings > JNI Optimization (now four
        // granular switches instead of this one combined toggle) - see PhoneSettingsFragment.

        changeResolutionRatioPreview(AllSettings.resolutionRatio.getValue())
        computeVisibility()
    }

    private fun changeResolutionRatioPreview(progress: Int) {
        binding.resolutionRatioPreview.text = getResolutionRatioPreview(resources, progress)
    }

    override fun onChange() {
        super.onChange()
        computeVisibility()
    }

    private fun computeVisibility() {
        binding.forceVsyncLayout.visibility =
            if (AllSettings.alternateSurface.getValue()) View.VISIBLE else View.GONE
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    companion object {
        const val TAG: String = "VideoSettingsFragment"

        @JvmStatic
        fun getResolutionRatioPreview(resources: Resources, progress: Int): String {
            val metrics = Tools.currentDisplayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || width > height
            val progressFloat = progress.toFloat() / 100F
            val previewWidth  = Tools.getDisplayFriendlyRes(if (isLandscape) width  else height, progressFloat)
            val previewHeight = Tools.getDisplayFriendlyRes(if (isLandscape) height else width,  progressFloat)
            return "$previewWidth x $previewHeight"
        }
    }
}
