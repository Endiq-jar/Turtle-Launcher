package com.endiq.turtlelauncher.ui.fragment.about

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.endiq.turtlelauncher.InfoCenter
import com.endiq.turtlelauncher.InfoDistributor
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentAboutInfoPageBinding
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.subassembly.about.AboutItemBean
import com.endiq.turtlelauncher.ui.subassembly.about.AboutItemBean.AboutItemButtonBean
import com.endiq.turtlelauncher.ui.subassembly.about.AboutRecyclerAdapter
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import com.endiq.turtlelauncher.utils.path.UrlManager

class AboutInfoPageFragment() : Fragment(R.layout.fragment_about_info_page) {
    private lateinit var binding: FragmentAboutInfoPageBinding
    private val mAboutData: MutableList<AboutItemBean> = ArrayList()
    private var parentPager2: ViewPager2? = null

    constructor(parentPager: ViewPager2): this() {
        this.parentPager2 = parentPager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutInfoPageBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadAboutData(requireContext().resources)

        val context = requireActivity()

        binding.apply {
            dec1.text = InfoCenter.replaceName(context, R.string.about_dec1)
            dec2.text = InfoCenter.replaceName(context, R.string.about_dec2)
            dec3.text = InfoCenter.replaceName(context, R.string.about_dec3)

            githubButton.setOnClickListener { ZHTools.openLink(requireActivity(), UrlManager.URL_HOME) }
            licenseButton.setOnClickListener { ZHTools.openLink(requireActivity(), "https://www.gnu.org/licenses/gpl-3.0.html") }

            val aboutAdapter = AboutRecyclerAdapter(this@AboutInfoPageFragment.mAboutData)
            aboutRecycler.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = aboutAdapter
                // TurtleLauncher: this page is a plain Fragment with no entry animation at
                // all, so its list appeared fully-formed. post{} so the children exist.
                post { TurtleTransitions.animateList(this) }
            }

            if (ZHTools.isChinese(requireActivity())) {
                qqGroupButton.visibility = View.VISIBLE
                qqGroupButton.setOnClickListener {
                    TipDialog.Builder(context)
                        .setTitle("QQ")
                        .setMessage("欢迎加入 ${InfoDistributor.APP_NAME} 官方 QQ 交流群（群号：${InfoCenter.QQ_GROUP}）！由于群人数有限，加入群聊前需要赞助 5元 或以上金额，请点击右侧“赞助开发”按钮访问爱发电。")
                        .setSelectable(true)
                        .setConfirm(R.string.generic_confirm)
                        .setShowCancel(false)
                        .showDialog()
                }
            } else {
                qqGroupButton.visibility = View.GONE
            }

            discordButton.setOnClickListener { ZHTools.openLink(requireActivity(), "https://discord.gg/8TfuMhM8tD") }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadAboutData(resources: Resources) {
        mAboutData.clear()

        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.ic_turtle_full, requireContext().theme),
                "Turtle Launcher",
                getString(R.string.about_TurtleLauncher_desc),
                AboutItemButtonBean(requireActivity(), "Github", UrlManager.URL_HOME)
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_developer, requireContext().theme),
                "Zenkairux",
                "Developer",
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_link),
                    UrlManager.URL_HOME
                )
            )
        )
    }
}

