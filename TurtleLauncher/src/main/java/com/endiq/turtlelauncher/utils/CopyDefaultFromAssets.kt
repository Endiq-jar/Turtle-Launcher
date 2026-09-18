package com.endiq.turtlelauncher.utils

import android.content.Context
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File
import java.io.IOException


class CopyDefaultFromAssets {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun copyFromAssets(context: Context?) {
            // Default control layout.
            if (checkDirectoryEmpty(PathManager.DIR_CTRLMAP_PATH)) {
                Tools.copyAssetFile(context, "default.json", PathManager.DIR_CTRLMAP_PATH, false)
            }
            Tools.copyAssetFile(context, "turtle_control_presets/survival.json", PathManager.DIR_CTRLMAP_PATH, "Survival.json", false)
        }

        private fun checkDirectoryEmpty(dir: String?): Boolean {
            val controlDir = dir?.let { File(it) }
            val files = controlDir?.listFiles()
            return files?.isEmpty() ?: true
        }
    }
}
