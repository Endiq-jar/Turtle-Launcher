package com.endiq.turtlelauncher.ui.fragment.settings
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentSettingsBinding
import com.endiq.turtlelauncher.setting.Settings
import com.endiq.turtlelauncher.ui.fragment.AboutFragment
import com.endiq.turtlelauncher.ui.fragment.AccountFragment
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.utils.ZHTools

/**
 * TurtleLauncher: a "Settings Hub" - a scrollable grid of tappable category cards, grouped
 * into General / Performance / Other, that drills into each settings category as its own
 * full-screen destination. Replaces the previous vertical-tab + ViewPager2 layout, and then
 * the single-column list that replaced that. Every existing settings category fragment is
 * kept and reused unchanged - this only replaces how you get to them, not what's inside them.
 * Loosely inspired by another launcher's card-grid settings screen, adapted rather than copied:
 * kept this app's existing General/Performance/Other grouping instead of one flat grid (14
 * categories reads better grouped), and added a live search field to jump straight to one
 * given how many categories there now are.
 */
class SettingsFragment : FragmentWithAnim(R.layout.fragment_settings) {
    companion object {
        const val TAG: String = "SettingsFragment"
    }

    private lateinit var binding: FragmentSettingsBinding

    /** Each hub card's row view, the section container it lives in, and the string resource
     *  its title is drawn from - used by the search filter below. Section headers aren't in
     *  this list; they're derived from their container's visibility instead. */
    private val searchableCards by lazy {
        listOf(
            Triple(binding.launcherSettingsRow, binding.generalSectionContainer, R.string.settings_row_launcher_title),
            Triple(binding.accountSettingsRow, binding.generalSectionContainer, R.string.settings_row_account_title),
            Triple(binding.shizukuSettingsRow, binding.generalSectionContainer, R.string.settings_row_shizuku_title),
            Triple(binding.videoSettingsRow, binding.performanceSectionContainer, R.string.settings_row_video_title),
            Triple(binding.gameSettingsRow, binding.performanceSectionContainer, R.string.settings_row_game_title),
            Triple(binding.javaSettingsRow, binding.performanceSectionContainer, R.string.settings_row_java_title),
            Triple(binding.hudSettingsRow, binding.performanceSectionContainer, R.string.settings_row_hud_title),
            Triple(binding.controlsSettingsRow, binding.performanceSectionContainer, R.string.settings_row_controls_title),
            Triple(binding.optimizationSettingsRow, binding.performanceSectionContainer, R.string.settings_row_optimization_title),
            Triple(binding.phoneSettingsRow, binding.performanceSectionContainer, R.string.settings_row_phone_title),
            Triple(binding.accessibilitySettingsRow, binding.performanceSectionContainer, R.string.settings_row_accessibility_title),
            Triple(binding.recordingSettingsRow, binding.performanceSectionContainer, R.string.settings_row_recording_title),
            Triple(binding.experimentalSettingsRow, binding.otherSectionContainer, R.string.settings_row_advanced_title),
            Triple(binding.aboutSettingsRow, binding.otherSectionContainer, R.string.settings_row_about_title),
        )
    }

    private val sectionHeaders by lazy {
        listOf(
            binding.generalSectionContainer to binding.generalSectionHeader,
            binding.performanceSectionContainer to binding.performanceSectionHeader,
            binding.otherSectionContainer to binding.otherSectionHeader,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSettingsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.backButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        binding.launcherSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, LauncherSettingsFragment::class.java, LauncherSettingsFragment.TAG, null)
        }
        binding.accountSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, AccountFragment::class.java, AccountFragment.TAG, null)
        }
        binding.shizukuSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, ShizukuSettingsFragment::class.java, ShizukuSettingsFragment.TAG, null)
        }
        binding.videoSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, VideoSettingsFragment::class.java, VideoSettingsFragment.TAG, null)
        }
        binding.gameSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, GameSettingsFragment::class.java, GameSettingsFragment.TAG, null)
        }
        binding.javaSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, JavaSettingsFragment::class.java, JavaSettingsFragment.TAG, null)
        }
        binding.hudSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, HudSettingsFragment::class.java, HudSettingsFragment.TAG, null)
        }
        binding.controlsSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, ControlSettingsFragment::class.java, ControlSettingsFragment.TAG, null)
        }
        binding.optimizationSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, OptimizationSettingsFragment::class.java, OptimizationSettingsFragment.TAG, null)
        }
        binding.phoneSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, PhoneSettingsFragment::class.java, PhoneSettingsFragment.TAG, null)
        }
        binding.accessibilitySettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, AccessibilitySettingsFragment::class.java, AccessibilitySettingsFragment.TAG, null)
        }
        binding.recordingSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, RecordingSettingsFragment::class.java, RecordingSettingsFragment.TAG, null)
        }
        binding.experimentalSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, ExperimentalSettingsFragment::class.java, ExperimentalSettingsFragment.TAG, null)
        }
        binding.aboutSettingsRow.setOnClickListener {
            ZHTools.swapFragmentWithAnim(this, AboutFragment::class.java, AboutFragment.TAG, null)
        }

        binding.searchClearButton.setOnClickListener { binding.searchInput.text?.clear() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.searchClearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                filterCards(s?.toString().orEmpty())
            }
        })
    }

    /** Live-filters the hub grid by card title. A section header (and its whole container)
     *  hides itself once none of its cards match, rather than leaving an empty group visible. */
    private fun filterCards(query: String) {
        val needle = query.trim().lowercase()
        var anyVisible = false

        searchableCards.forEach { (row, _, titleRes) ->
            val matches = needle.isEmpty() || getString(titleRes).lowercase().contains(needle)
            row.visibility = if (matches) View.VISIBLE else View.GONE
            if (matches) anyVisible = true
        }

        sectionHeaders.forEach { (container, header) ->
            val sectionHasVisibleCard = searchableCards.any { (row, cardContainer, _) ->
                cardContainer === container && row.visibility == View.VISIBLE
            }
            container.visibility = if (sectionHasVisibleCard) View.VISIBLE else View.GONE
            header.visibility = container.visibility
        }

        binding.settingsEmptyResult.visibility = if (!anyVisible && needle.isNotEmpty()) View.VISIBLE else View.GONE
        if (!anyVisible && needle.isNotEmpty()) {
            binding.settingsEmptyResult.text = getString(R.string.settings_hub_empty_result, query.trim())
        }
    }

    override fun onResume() {
        super.onResume()
        Settings.refreshSettings()
    }

    /** TurtleLauncher: settings is the panel you reach *down* to from the home screen, so it
     *  deliberately rises off the bottom edge of the display and settles with a bounce rather
     *  than drifting in from wherever the global transition would put it. Same reasoning as
     *  the mods screen keeping its Wobble: a per-screen design choice, not an oversight.
     *  "Sheet" is also selectable globally in the two transition pickers if you want this
     *  entrance everywhere instead. */
    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.settingsLayout, TurtleTransitions.sheetEnter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.settingsLayout, TurtleTransitions.sheetExit()))
    }
}
