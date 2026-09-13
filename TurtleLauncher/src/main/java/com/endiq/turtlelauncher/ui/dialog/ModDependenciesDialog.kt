package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogModDependenciesBinding
import com.endiq.turtlelauncher.feature.download.ModDependenciesAdapter
import com.endiq.turtlelauncher.feature.download.item.DependenciesInfoItem
import com.endiq.turtlelauncher.feature.download.item.InfoItem
import com.endiq.turtlelauncher.feature.log.Logging
import net.kdt.pojavlaunch.Tools
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions


class ModDependenciesDialog(
    context: Context,
    private val infoItem: InfoItem,
    private val dependenciesData: List<DependenciesInfoItem>,
    private val install: () -> Unit
) : FullScreenDialog(context) {
    private val binding = DialogModDependenciesBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setCancelable(false)
        setContentView(binding.root)

        window?.apply {
            val dimension = Tools.dpToPx(context.resources.getDimension(R.dimen._12sdp)).toInt()
            attributes.width = Tools.currentDisplayMetrics.widthPixels - 2 * dimension
            attributes.height = Tools.currentDisplayMetrics.heightPixels - 2 * dimension

            setGravity(Gravity.CENTER)

            //隐藏状态栏
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        runCatching {
            init()
        }.getOrElse {
            dismiss()
            Logging.e("ModDependenciesDialog", "Initialization failed, dismiss attempted.", it)
        }
    }

    private fun init() {
        val data = dependenciesData.toMutableList().apply { this.sort() }

        binding.apply {
            titleView.text = context.getString(R.string.download_install_dependencies, infoItem.title)
            downloadButton.text = context.getString(R.string.download_install, infoItem.title)

            recyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                layoutAnimation = TurtleTransitions.listLayoutAnimationController(context)
                adapter = ModDependenciesAdapter(infoItem, data).apply {
                    setOnItemCLickListener { dismiss() }
                }
            }

            closeButton.setOnClickListener { dismiss() }
            downloadButton.setOnClickListener {
                install()
                dismiss()
            }
        }
    }
}
