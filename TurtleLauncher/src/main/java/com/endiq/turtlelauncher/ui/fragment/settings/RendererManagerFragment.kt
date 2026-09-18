package com.endiq.turtlelauncher.ui.fragment.settings
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentRendererManagerBinding
import com.endiq.turtlelauncher.feature.download.RendererCardAdapter
import com.endiq.turtlelauncher.feature.pluginupdate.PluginAssetInfo
import com.endiq.turtlelauncher.feature.pluginupdate.PluginKind
import com.endiq.turtlelauncher.feature.pluginupdate.PluginUpdateManager
import com.endiq.turtlelauncher.renderer.RendererCatalog
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.renderer.renderers.HolyGL4ESRenderer
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.utils.ZHTools
import net.endiq.launcher.Tools
import java.io.File

/**
 * Material-styled renderer picker: one card per renderer with a stability badge
 * (Recommended/Stable/Experimental) and a compatibility note - version range, missing
 * bundled library, or a Vulkan requirement - plus a checkmark on the current selection
 * and a reset-to-defaults action. Complements rather than replaces the existing quick-pick
 * list in Video settings; that one's still there for a fast switch, this one's for
 * actually deciding what to pick.
 */
class RendererManagerFragment : FragmentWithAnim(R.layout.fragment_renderer_manager) {
    companion object {
        const val TAG: String = "RendererManagerFragment"
    }

    private lateinit var binding: FragmentRendererManagerBinding

    /** Cached once per screen-open so re-rendering after picking a renderer doesn't
     *  re-hit GitHub every time; refreshed after a successful install. */
    private var downloadableCards: List<RendererCardAdapter.CardEntry> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentRendererManagerBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rendererGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        render()
        fetchAlwaysOfferedRenderers()

        binding.rendererManagerReset.setOnClickListener {
            val defaultUuid = HolyGL4ESRenderer().getUniqueIdentifier()
            AllSettings.renderer.put(defaultUuid).save()
            Renderers.setCurrentRenderer(requireContext(), defaultUuid)
            render()
        }

        binding.rendererManagerReturn.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
    }

    /**
     * TurtleLauncher: LTW and MobileGlues already had a real, working download+install
     * path via PluginUpdateManager, it just wasn't reachable from this screen at all.
     * Rather than bolt on a separate "browse plugins" button/dialog, fetch them the
     * moment this screen opens and fold them straight into the same grid every other
     * renderer lives in - if not installed yet they simply show up here already,
     * badged "tap to download", right next to the ones that are ready to use.
     */
    private fun fetchAlwaysOfferedRenderers() {
        val context = requireContext()
        PluginUpdateManager.checkForUpdates(context, force = true) { updates, _ ->
            if (!isAdded) return@checkForUpdates

            val alreadyLoadedNames = Renderers.getCompatibleRenderers(context).second
                .map { it.getRendererName().lowercase() }

            val ltw = updates.firstOrNull {
                it.kind == PluginKind.RENDERER && it.assetName.contains("ltw", ignoreCase = true)
            }
            val mobileGlues = updates.firstOrNull {
                it.kind == PluginKind.RENDERER && it.assetName.contains("mobileglues", ignoreCase = true)
            }

            downloadableCards = listOfNotNull(
                ltw?.takeIf { "ltw" !in alreadyLoadedNames.joinToString() }?.let { toDownloadCard(it, "LTW") },
                mobileGlues?.takeIf { "mobileglues" !in alreadyLoadedNames.joinToString().replace(" ", "") }
                    ?.let { toDownloadCard(it, "MobileGlues") }
            )
            if (downloadableCards.isNotEmpty()) render()
        }
    }

    private fun toDownloadCard(asset: PluginAssetInfo, displayName: String) = RendererCardAdapter.CardEntry(
        uniqueIdentifier = "download:${asset.assetName}",
        name = displayName,
        badge = RendererCatalog.Badge.EXPERIMENTAL,
        compatNote = getString(R.string.renderer_tap_to_download),
        isSelected = false,
        downloadAsset = asset
    )

    private fun installRendererAsset(asset: PluginAssetInfo) {
        val context = requireContext()
        Toast.makeText(context, getString(R.string.renderer_get_more_installing, asset.assetName), Toast.LENGTH_SHORT).show()
        PluginUpdateManager.downloadAndInstall(context, asset) { success, message ->
            if (!isAdded) return@downloadAndInstall
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (success) {
                downloadableCards = downloadableCards.filterNot { it.downloadAsset == asset }
                render()
            }
        }
    }

    private fun render() {
        val context = requireContext()
        val compatibleRenderers = Renderers.getCompatibleRenderers(context).second
        val currentUuid = AllSettings.renderer.getValue()
        val hasVulkan = Tools.checkVulkanSupport(context.packageManager)

        val installedCards = compatibleRenderers.map { renderer ->
            val catalogEntry = RendererCatalog.get(renderer.getRendererId())
            val plugin = com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager.getRendererList()
                .find { it.uniqueIdentifier == renderer.getUniqueIdentifier() }
            val compatNote = buildCompatNote(renderer, catalogEntry, plugin, hasVulkan)
            RendererCardAdapter.CardEntry(
                uniqueIdentifier = renderer.getUniqueIdentifier(),
                name = renderer.getRendererName(),
                badge = catalogEntry?.badge ?: RendererCatalog.Badge.EXPERIMENTAL,
                compatNote = compatNote,
                isSelected = renderer.getUniqueIdentifier() == currentUuid
            )
        }

        binding.rendererGrid.adapter = RendererCardAdapter(installedCards + downloadableCards) { card ->
            if (card.downloadAsset != null) {
                installRendererAsset(card.downloadAsset)
            } else {
                AllSettings.renderer.put(card.uniqueIdentifier).save()
                Renderers.setCurrentRenderer(context, card.uniqueIdentifier)
                render()
            }
        }
    }

    /** Missing-library warning takes priority over version-range notes - a renderer that
     *  will crash on load is more urgent to know about than its recommended version range. */
    private fun buildCompatNote(
        renderer: com.endiq.turtlelauncher.renderer.RendererInterface,
        catalogEntry: RendererCatalog.Entry?,
        plugin: com.endiq.turtlelauncher.plugins.renderer.RendererPlugin?,
        hasVulkan: Boolean
    ): String? {
        // Same existence rule as Renderers.hasRequiredLibrary: built-in renderers name a
        // bare library inside the launcher's own jniLibs folder, plugin renderers bring
        // their own library and reference it by absolute path inside the plugin.
        val library = renderer.getRendererLibrary()
        val libMissing = if (library.startsWith("/")) !File(library).exists()
        else !File(com.endiq.turtlelauncher.utils.path.PathManager.DIR_NATIVE_LIB, library).exists()
        if (libMissing) return getString(R.string.renderer_compat_missing_library)

        if (renderer.getRendererId() == com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer.ID && !hasVulkan) {
            return getString(R.string.renderer_compat_requires_vulkan)
        }

        // The version range a renderer card shows comes from the built-in RendererCatalog
        // for first-class renderers; an installed plugin app ships its own range in its
        // manifest (minMCVer/maxMCVer) and surfaces it the same way, so a plugin renderer
        // documents its supported versions exactly like MobileGlues and friends do.
        val maxVersion = catalogEntry?.maxMinecraftVersion ?: plugin?.maxMinecraftVersion
        val minVersion = catalogEntry?.minMinecraftVersion ?: plugin?.minMinecraftVersion
        maxVersion?.let { return getString(R.string.renderer_compat_max_version, it) }
        minVersion?.let { return getString(R.string.renderer_compat_min_version, it) }
        return null
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.root, TurtleTransitions.exit()))
    }
}
