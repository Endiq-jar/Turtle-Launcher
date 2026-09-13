package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import com.endiq.turtlelauncher.databinding.DialogWhatsNewBinding
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener


class WhatsNewDialog(context: Context) :
    FullScreenDialog(context), DialogInitializationListener {

    private val binding = DialogWhatsNewBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.closeButton.setOnClickListener { dismiss() }
        DraggableDialog.initDialog(this)
    }

    override fun onInit(): Window? = window
}
