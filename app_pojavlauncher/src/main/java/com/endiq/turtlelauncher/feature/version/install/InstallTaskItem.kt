package com.endiq.turtlelauncher.feature.version.install

import android.app.Activity
import java.io.File

/**
 * Wrapper around InstallTask that records more detailed information.
 * @see InstallTask
 */
class InstallTaskItem(
    val selectedVersion: String,
    val isMod: Boolean,
    val task: InstallTask,
    val endTask: EndTask?
) {
    override fun toString(): String {
        return "InstallTaskItem{selectedVersion='$selectedVersion', isMod='$isMod'}"
    }

    fun interface EndTask {
        /**
         * Run the ModLoader installation through this task.
         * @param activity the current Activity, used to show the JRE picker and switch to the Java GUI
         * @param file the file produced by the previous task
         */
        @Throws(Throwable::class)
        fun endTask(activity: Activity, file: File)
    }
}