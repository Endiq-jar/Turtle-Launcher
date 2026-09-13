package com.endiq.turtlelauncher.feature.mod

import com.endiq.turtlelauncher.utils.file.FileTools.Companion.renameFile
import java.io.File

class ModUtils {
    companion object {
        const val JAR_FILE_SUFFIX: String = ".jar"
        const val DISABLE_JAR_FILE_SUFFIX: String = "$JAR_FILE_SUFFIX.disabled"

        @JvmStatic
        fun disableMod(file: File?) {
            file ?: return
            val fileName = file.name
            val fileParent = file.parent
            val newFile = File(fileParent, "$fileName.disabled")
            renameFile(file, newFile)
        }

        @JvmStatic
        fun enableMod(file: File?) {
            file ?: return
            val fileName = file.name
            val fileParent = file.parent
            // lastIndexOf() returns -1 when the suffix is absent (e.g. enabling an
            // already-enabled mod) - substring(0, -1) would throw StringIndexOutOfBounds.
            val suffixIndex = fileName.lastIndexOf(DISABLE_JAR_FILE_SUFFIX)
            var newFileName = if (suffixIndex == -1) fileName else fileName.substring(0, suffixIndex)
            if (!fileName.endsWith(JAR_FILE_SUFFIX)) newFileName += JAR_FILE_SUFFIX //如果没有.jar结尾，那么默认加上.jar后缀

            val newFile = File(fileParent, newFileName)
            renameFile(file, newFile)
        }
    }
}