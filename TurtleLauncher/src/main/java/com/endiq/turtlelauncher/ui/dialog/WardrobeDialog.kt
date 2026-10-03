package com.endiq.turtlelauncher.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import com.endiq.turtlelauncher.databinding.DialogWardrobeBinding
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener
import com.endiq.turtlelauncher.utils.skin.SkinLoader
import dev.storeforminecraft.skinviewandroid.library.threedimension.ui.SkinView3DSurfaceView
import net.endiq.launcher.value.MinecraftAccount

/**
 * Single "Wardrobe" entry point replacing the old separate skin/cape icon buttons
 * (one per account row, and a skin-only "Edit" button in the account detail panel).
 * Shows the account's live 3D skin preview - same render path as
 * AccountFragment.updateAccountDetail() - then routes to the existing [SkinCapeDialog]
 * for the actual skin or cape editing (gallery/URL pickers, uploads, history), which
 * is untouched.
 */
class WardrobeDialog(
    private val activity: AppCompatActivity,
    private val account: MinecraftAccount
) : FullScreenDialog(activity), DialogInitializationListener {

    private val binding = DialogWardrobeBinding.inflate(LayoutInflater.from(activity))
    private var previewView: SkinView3DSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        loadPreview()

        binding.wardrobeChangeSkin.setOnClickListener {
            dismiss()
            SkinCapeDialog(activity, account, "skin").show()
        }
        binding.wardrobeChangeCape.setOnClickListener {
            dismiss()
            SkinCapeDialog(activity, account, "cape").show()
        }
        binding.wardrobeClose.setOnClickListener { dismiss() }

        checkHeight(binding.root, binding.contentView, binding.scrollView)
        DraggableDialog.initDialog(this)
    }

    private fun loadPreview() {
        runCatching {
            val bitmap = SkinLoader.getSkinBitmap(activity, account)
            val current = binding.wardrobeSkinPreview
            val container = current.parent as android.view.ViewGroup
            val index = container.indexOfChild(current)
            val fresh = SkinView3DSurfaceView(activity).apply {
                id = current.id
                layoutParams = current.layoutParams
            }
            container.removeView(current)
            container.addView(fresh, index)
            fresh.render(bitmap)
            fresh.onResume()
            previewView = fresh
            binding.wardrobeLoading.visibility = android.view.View.GONE
        }.onFailure { e ->
            Logging.e("WardrobeDialog", "Failed to load 3D skin preview.", e)
            binding.wardrobeLoading.visibility = android.view.View.GONE
        }
    }

    override fun onStop() {
        super.onStop()
        previewView?.onPause()
    }

    override fun onInit(): Window? = window
}
