package com.endiq.turtlelauncher.feature.unpack

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging.e
import com.endiq.turtlelauncher.utils.CopyDefaultFromAssets.Companion.copyFromAssets
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools

class UnpackSingleFilesTask(val context: Context) : AbstractUnpackTask() {
    override fun isNeedUnpack(): Boolean = true

    override fun run() {
        runCatching {
            copyFromAssets(context)
            Tools.copyAssetFile(context, "resolv.conf", PathManager.DIR_DATA, false)
        }.getOrElse { e("AsyncAssetManager", "Failed to unpack critical components !") }
    }
}