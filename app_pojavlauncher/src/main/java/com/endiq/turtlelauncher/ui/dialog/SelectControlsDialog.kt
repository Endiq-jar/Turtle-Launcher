package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import com.endiq.turtlelauncher.ui.subassembly.customcontrols.ControlSelectedListener
import com.endiq.turtlelauncher.ui.subassembly.customcontrols.ControlsListViewCreator
import java.io.File

class SelectControlsDialog(
    context: Context,
    private val selectedListener: SelectedListener
) : AbstractSelectDialog(context) {

    override fun initDialog(recyclerView: RecyclerView) {
        ControlsListViewCreator(context, recyclerView).apply {
            listAtPath()
            setSelectedListener(object : ControlSelectedListener() {
                override fun onItemSelected(file: File) {
                    selectedListener.onSelected(file)
                    dismiss()
                }

                override fun onItemLongClick(file: File) {
                }
            })
        }
    }

    interface SelectedListener {
        fun onSelected(file: File)
    }
}
