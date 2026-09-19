package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.FragmentVersionBinding
import com.endiq.turtlelauncher.event.sticky.MinecraftVersionValueEvent
import com.endiq.turtlelauncher.feature.download.SeriesCardAdapter
import com.endiq.turtlelauncher.feature.download.utils.VersionSeriesUtils
import com.endiq.turtlelauncher.utils.ZHTools
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class VersionSelectorFragment : FragmentWithAnim(R.layout.fragment_version) {
    companion object {
        const val TAG: String = "FileSelectorFragment"
    }

    private lateinit var binding: FragmentVersionBinding
    private var allCards: List<SeriesCardAdapter.CardEntry> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentVersionBinding.inflate(layoutInflater)
        return binding.root
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.apply {
            allCards = buildCards()
            seriesGrid.layoutManager = GridLayoutManager(requireContext(), 3)
            renderCards(allCards)

            searchVersion.doAfterTextChanged { text -> applyFilter(text?.toString()) }

            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
        }
    }

    private fun applyFilter(query: String?) {
        val trimmed = query?.trim().orEmpty()
        renderCards(
            if (trimmed.isEmpty()) allCards
            else allCards.filter { it.label.contains(trimmed, ignoreCase = true) }
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onVersionListUpdated(event: MinecraftVersionValueEvent) {
        if (!isAdded || view == null) return
        allCards = buildCards()
        applyFilter(binding.searchVersion.text?.toString())
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    private fun renderCards(cards: List<SeriesCardAdapter.CardEntry>) {
        binding.seriesGrid.adapter = SeriesCardAdapter(cards) { card ->
            val bundle = Bundle()
            bundle.putString(VersionSeriesDetailFragment.BUNDLE_SERIES_LABEL, card.label)
            ZHTools.swapFragmentWithAnim(this, VersionSeriesDetailFragment::class.java, VersionSeriesDetailFragment.TAG, bundle)
        }
        binding.seriesGrid.post { TurtleTransitions.animateList(binding.seriesGrid) }
    }

    private fun buildCards(): List<SeriesCardAdapter.CardEntry> {
        val grouped = VersionSeriesUtils.group(VersionSeriesUtils.fetchAllVersions())
        val newestSeriesLabel = grouped.seriesCards.firstOrNull()?.seriesLabel

        val cards = mutableListOf<SeriesCardAdapter.CardEntry>()
        grouped.seriesCards.forEach { series ->
            cards.add(
                SeriesCardAdapter.CardEntry(
                    label = series.seriesLabel,
                    versionCount = series.versions.size,
                    iconRes = R.drawable.ic_minecraft,
                    isLatest = series.seriesLabel == newestSeriesLabel,
                    versions = series.versions
                )
            )
        }
        if (grouped.betaVersions.isNotEmpty()) {
            cards.add(
                SeriesCardAdapter.CardEntry(
                    label = getString(R.string.version_beta),
                    versionCount = grouped.betaVersions.size,
                    iconRes = R.drawable.ic_old_cobblestone,
                    isLatest = false,
                    versions = grouped.betaVersions
                )
            )
        }
        if (grouped.alphaVersions.isNotEmpty()) {
            cards.add(
                SeriesCardAdapter.CardEntry(
                    label = getString(R.string.version_alpha),
                    versionCount = grouped.alphaVersions.size,
                    iconRes = R.drawable.ic_old_grass_block,
                    isLatest = false,
                    versions = grouped.alphaVersions
                )
            )
        }
        return cards
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.versionLayout, TurtleTransitions.enter()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.versionLayout, TurtleTransitions.exit()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.exit()))
    }
}
