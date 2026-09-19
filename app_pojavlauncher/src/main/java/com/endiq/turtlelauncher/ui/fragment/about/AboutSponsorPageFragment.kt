package com.endiq.turtlelauncher.ui.fragment.about
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.FragmentAboutSponsorPageBinding
import com.endiq.turtlelauncher.feature.CheckSponsor
import com.endiq.turtlelauncher.feature.CheckSponsor.Companion.check
import com.endiq.turtlelauncher.feature.CheckSponsor.Companion.getSponsorData
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.about.SponsorMeta
import com.endiq.turtlelauncher.ui.subassembly.about.SponsorRecyclerAdapter

class AboutSponsorPageFragment : Fragment(R.layout.fragment_about_sponsor_page) {
    private lateinit var binding: FragmentAboutSponsorPageBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutSponsorPageBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        check(object : CheckSponsor.CheckListener {
            override fun onFailure() {
                setSponsorVisible(false)
            }

            override fun onSuccessful(data: SponsorMeta?) {
                setSponsorVisible(true)
            }
        })
    }

    private fun setSponsorVisible(visible: Boolean) {
        TaskExecutors.runInUIThread {
            try {
                binding.sponsorLayout.visibility = if (visible) {
                    binding.sponsorRecycler.apply {
                        layoutManager = LinearLayoutManager(requireContext())
                        adapter = SponsorRecyclerAdapter(getSponsorData())
                    }
                    binding.loadingProgress.visibility = View.GONE
                    TurtleTransitions.setVisibilityAnimated(binding.sponsorLayout, true)
                    View.VISIBLE
                } else View.GONE
            } catch (e: Exception) {
                Logging.e("setSponsorVisible", e.toString())
            }
        }
    }
}

