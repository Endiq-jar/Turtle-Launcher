package com.endiq.turtlelauncher.utils.runtime

import android.content.Context
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.SelectRuntimeDialog

class SelectRuntimeUtils {
    companion object {
        @JvmStatic
        fun selectRuntime(context: Context, dialogTitle: String?, selectedListener: RuntimeSelectedListener) {
            TaskExecutors.runInUIThread {
                when (AllSettings.selectRuntimeMode.getValue()) {
                    "ask_me" -> SelectRuntimeDialog(context, selectedListener).apply {
                        dialogTitle?.let { setTitleText(it) }
                    }.show()
                    "default" -> selectedListener.onSelected(AllSettings.defaultRuntime.getValue().takeIf { it.isNotEmpty() })
                    "auto" -> selectedListener.onSelected(null)
                    else -> {}
                }
            }
        }
    }
}