package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import com.endiq.turtlelauncher.databinding.DialogProgressBinding
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.formatFileSize


class ProgressDialog(
    context: Context,
    private val listener: OnCancelListener
) : FullScreenDialog(context), DialogInitializationListener {
    private val binding = DialogProgressBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        setCancelable(false)

        binding.progressBar.setMax(1000)
        binding.cancelButton.setOnClickListener {
            if (!listener.onClick()) return@setOnClickListener
            dismiss()
        }

        DraggableDialog.initDialog(this)
    }

    fun updateText(text: String?) {
        binding.textView.text = text
    }

    fun updateRate(processingRate: Long) {
        if (processingRate > 0) binding.uploadRate.visibility = View.VISIBLE
        val formatFileSize = formatFileSize(processingRate)
        "$formatFileSize/s".also { binding.uploadRate.text = it }
    }

    fun updateProgress(progress: Double, total: Double) {
        val doubleValue = if (total > 0) (progress / total * 1000) else 0.0
        val intValue = doubleValue.toInt().coerceIn(0, 1000)

        binding.progressBar.apply {
            visibility = if (doubleValue > 0) View.VISIBLE else View.GONE
            setProgress(intValue, AllSettings.animation.getValue())
        }
    }

    override fun onInit(): Window? = window

    fun interface OnCancelListener {
        fun onClick(): Boolean
    }
}
