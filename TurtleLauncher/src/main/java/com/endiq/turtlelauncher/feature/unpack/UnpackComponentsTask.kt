package com.endiq.turtlelauncher.feature.unpack

import android.content.Context
import android.content.res.AssetManager
import com.endiq.turtlelauncher.feature.log.Logging.i
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class UnpackComponentsTask(val context: Context, val component: Components) : AbstractUnpackTask() {
    private lateinit var am: AssetManager
    private lateinit var rootDir: String
    private lateinit var versionFile: File
    private lateinit var input: InputStream
    private var isCheckFailed: Boolean = false

    init {
        runCatching {
            am = context.assets
            rootDir = if (component.privateDirectory) PathManager.DIR_DATA else PathManager.DIR_GAME_HOME
            versionFile = File("$rootDir/${component.component}/version")
            input = am.open("components/${component.component}/version")
        }.getOrElse {
            isCheckFailed = true
        }
    }

    fun isCheckFailed() = isCheckFailed

    override fun isNeedUnpack(): Boolean {
        if (isCheckFailed) return false

        if (!versionFile.exists()) {
            requestEmptyParentDir(versionFile)
            i("Unpack Components", "${component.component}: Pack was installed manually, or does not exist...")
            return true
        } else {
            val fis = FileInputStream(versionFile)
            val release1 = Tools.read(input)
            val release2 = Tools.read(fis)
            if (release1 != release2) {
                requestEmptyParentDir(versionFile)
                return true
            } else {
                i("UnpackPrep", "${component.component}: Pack is up-to-date with the launcher, continuing...")
                return false
            }
        }
    }

    override fun run() {
        listener?.onTaskStart()
        // AssetManager.list() returns null when the path is not an asset directory -
        // still signal onTaskEnd so callers waiting on it don't hang forever.
        val fileList = am.list("components/${component.component}")
        if (fileList == null) {
            i("Unpack Components", "${component.component}: asset directory missing, nothing to unpack")
            listener?.onTaskEnd()
            return
        }
        for (fileName in fileList) {
            Tools.copyAssetFile(context, "components/${component.component}/$fileName", "$rootDir/${component.component}", true)
        }
        listener?.onTaskEnd()
    }

    private fun requestEmptyParentDir(file: File) {
        val parent = file.parentFile ?: return
        parent.apply {
            if (exists() and isDirectory) {
                FileUtils.deleteDirectory(this)
            }
            mkdirs()
        }
    }
}