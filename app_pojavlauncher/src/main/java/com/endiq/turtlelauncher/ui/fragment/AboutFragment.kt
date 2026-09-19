package com.endiq.turtlelauncher.ui.fragment

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.FragmentAboutBinding
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.fragment.about.AboutInfoPageFragment
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.path.UrlManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils


class AboutFragment : FragmentWithAnim(R.layout.fragment_about) {
    companion object {
        const val TAG: String = "AboutFragment"
    }

    private lateinit var binding: FragmentAboutBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViewPager()

        binding.apply {
            appInfo.text = StringUtils.insertNewline(StringUtils.insertSpace(getString(R.string.about_version_name), ZHTools.getVersionName()),
                StringUtils.insertSpace(getString(R.string.about_version_code), ZHTools.getVersionCode()),
                StringUtils.insertSpace(getString(R.string.about_last_update_time), ZHTools.getLastUpdateTime(requireContext())),
                StringUtils.insertSpace(getString(R.string.about_version_status), ZHTools.getVersionStatus(requireContext())))
            appInfo.setOnClickListener{ StringUtils.copyText("text", appInfo.text.toString(), requireContext()) }

            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
            supportDevelopment.setOnClickListener {
                TipDialog.Builder(requireActivity())
                    .setTitle(R.string.request_sponsorship_title)
                    .setMessage(R.string.request_sponsorship_message)
                    .setConfirm(R.string.about_button_support_development)
                    .setConfirmClickListener { ZHTools.openLink(requireActivity(), UrlManager.URL_SUPPORT) }
                    .showDialog()
            }
        }
    }

    private fun initViewPager() {
        binding.infoViewPager.apply {
            adapter = ViewPagerAdapter(requireActivity(), this)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.infoViewPager, TurtleTransitions.enter()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.infoViewPager, TurtleTransitions.exit()))
        animPlayer.apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.exit()))
    }

    private class ViewPagerAdapter(
        fragmentActivity: FragmentActivity,
        private val viewPager: ViewPager2
    ): FragmentStateAdapter(fragmentActivity) {
        override fun getItemCount(): Int = 1
        override fun createFragment(position: Int): Fragment {
            return AboutInfoPageFragment(viewPager)
        }
    }
}

