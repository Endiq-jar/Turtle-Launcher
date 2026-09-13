package com.endiq.turtlelauncher.ui.fragment

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentDownloadBinding
import com.endiq.turtlelauncher.event.value.DownloadPageEvent
import com.endiq.turtlelauncher.event.value.DownloadPageEvent.PageSwapEvent.Companion.IN
import com.endiq.turtlelauncher.event.value.DownloadPageEvent.PageSwapEvent.Companion.OUT
import com.endiq.turtlelauncher.ui.fragment.download.resource.ModDownloadFragment
import com.endiq.turtlelauncher.ui.fragment.download.resource.ModPackDownloadFragment
import com.endiq.turtlelauncher.ui.fragment.download.resource.ResourcePackDownloadFragment
import com.endiq.turtlelauncher.ui.fragment.download.resource.ShaderPackDownloadFragment
import com.endiq.turtlelauncher.ui.fragment.download.resource.WorldDownloadFragment
import org.greenrobot.eventbus.EventBus


class DownloadFragment : FragmentWithAnim(R.layout.fragment_download) {
    companion object {
        const val TAG = "DownloadFragment"
        /** Bundle key: which ViewPager tab to open on (defaults to 0/Mods if absent). */
        const val ARG_INITIAL_TAB = "initial_tab"
        /** Bundle key: forwarded to the target tab fragment's own initialSearchQuery(). */
        const val ARG_INITIAL_QUERY = "initial_query"
    }

    private lateinit var binding: FragmentDownloadBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDownloadBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViewPager()

        binding.classifyTab.observeIndexChange { _, toIndex, reselect, fromUser ->
            if (reselect) return@observeIndexChange
            if (fromUser) binding.downloadViewpager.setCurrentItem(toIndex, false)
        }

        val initialTab = arguments?.getInt(ARG_INITIAL_TAB, -1) ?: -1
        if (initialTab >= 0) {
            binding.downloadViewpager.setCurrentItem(initialTab, false)
            binding.classifyTab.onPageSelected(initialTab)
        }
    }

    private fun initViewPager() {
        binding.downloadViewpager.apply {
            adapter = ViewPagerAdapter(this@DownloadFragment)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            isUserInputEnabled = false
            registerOnPageChangeCallback(object: OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    onFragmentSelect(position)
                    EventBus.getDefault().post(DownloadPageEvent.PageSwapEvent(position, IN))
                }
            })
        }
    }

    private fun onFragmentSelect(position: Int) {
        binding.classifyTab.onPageSelected(position)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.classifyLayout, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.classifyLayout, TurtleTransitions.exit()))
        EventBus.getDefault().post(DownloadPageEvent.PageSwapEvent(binding.classifyTab.currentItemIndex, OUT))
    }

    override fun onDestroyView() {
        EventBus.getDefault().post(DownloadPageEvent.PageDestroyEvent())
        super.onDestroyView()
    }

    private inner class ViewPagerAdapter(private val hostFragment: Fragment): FragmentStateAdapter(hostFragment.requireActivity()) {
        override fun getItemCount(): Int = 5
        override fun createFragment(position: Int): Fragment {
            return when(position) {
                1 -> ModPackDownloadFragment(hostFragment).apply {
                    val query = this@DownloadFragment.arguments?.getString(ARG_INITIAL_QUERY)
                    this.arguments = Bundle().apply {
                        query?.let { putString(ModPackDownloadFragment.ARG_INITIAL_QUERY, it) }
                    }
                }
                2 -> ResourcePackDownloadFragment(hostFragment)
                3 -> WorldDownloadFragment(hostFragment)
                4 -> ShaderPackDownloadFragment(hostFragment)
                else -> ModDownloadFragment(hostFragment)
            }
        }
    }
}
