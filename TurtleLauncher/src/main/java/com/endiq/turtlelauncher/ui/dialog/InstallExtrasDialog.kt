package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogInstallExtrasBinding
import com.endiq.turtlelauncher.feature.log.Logging
import net.endiq.launcher.Tools

/**
 * TurtleLauncher: popup shown every time the install action on InstallGameFragment is
 * pressed, before anything is actually installed. Lets the user opt the Turtle Client
 * and/or FPS Boost mods into the install alongside whatever loader they already picked
 * on the main screen.
 *
 * Neither extra has a real mod resource wired up yet (no Modrinth slug/URL/jar
 * assigned) - see ExtraModInstall for the hook this feeds into once one exists. Today,
 * toggling a row here only flips the boolean passed to [onInstall]; nothing is
 * downloaded for it yet.
 */
class InstallExtrasDialog(
    context: Context,
    private val onInstall: (includeTurtleClient: Boolean, includeFpsBoost: Boolean) -> Unit
) : FullScreenDialog(context) {
    private val binding = DialogInstallExtrasBinding.inflate(layoutInflater)

    private var turtleClientSelected = false
    private var fpsBoostSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setCancelable(true)
        setContentView(binding.root)

        window?.apply {
            val dimension = Tools.dpToPx(context.resources.getDimension(R.dimen._12sdp)).toInt()
            attributes.width = Tools.currentDisplayMetrics.widthPixels - 2 * dimension
            attributes.height = Tools.currentDisplayMetrics.heightPixels - 2 * dimension

            setGravity(Gravity.CENTER)

            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        runCatching {
            init()
        }.getOrElse {
            dismiss()
            Logging.e("InstallExtrasDialog", "Initialization failed, dismiss attempted.", it)
        }
    }

    private fun init() {
        binding.apply {
            turtleClientOption.setOnClickListener {
                turtleClientSelected = !turtleClientSelected
                applySelected(turtleClientOption, turtleClientCheck, turtleClientSelected)
            }
            fpsBoostOption.setOnClickListener {
                fpsBoostSelected = !fpsBoostSelected
                applySelected(fpsBoostOption, fpsBoostCheck, fpsBoostSelected)
            }

            closeButton.setOnClickListener { dismiss() }
            installButton.setOnClickListener {
                onInstall(turtleClientSelected, fpsBoostSelected)
                dismiss()
            }
        }
    }

    private fun applySelected(row: ConstraintLayout, check: View, selected: Boolean) {
        row.isSelected = selected
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }
}
