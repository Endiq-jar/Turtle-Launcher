package com.endiq.turtlelauncher.ui.subassembly.filelist

import android.graphics.drawable.Drawable
import com.endiq.turtlelauncher.feature.turtle.heatmap.PerfEstimate
import com.endiq.turtlelauncher.utils.stringutils.SortStrings.Companion.compareChar
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.Date

class FileItemBean(
    @JvmField val name: String,
    @JvmField val date: Date?,
    @JvmField val size: Long?
) : Comparable<FileItemBean?> {
    @JvmField var image: Drawable? = null
    @JvmField var file: File? = null
    @JvmField var isHighlighted: Boolean = false
    @JvmField var isCanCheck: Boolean = true
    @JvmField var hasUpdate: Boolean = false
    @JvmField var perfEstimate: PerfEstimate? = null

    constructor(file: File) : this(
        file.name,
        Date(file.lastModified()),
        // Summing folder sizes takes too long; only show the size of files.
        if (file.isFile) FileUtils.sizeOf(file) else null
    ) {
        this.file = file
    }

    constructor(name: String, image: Drawable?) : this(name, null as Date?, null) {
        this.image = image
    }

    constructor(name: String, date: Date, image: Drawable?) : this(name, date, null as Long?) {
        this.image = image
    }

    override fun compareTo(other: FileItemBean?): Int {
        // Sorting a list that contains null used to throw NPE out of the sort and
        // crash the caller - treat null as greater instead so the sort survives.
        other ?: return 1

        val thisName = file?.name ?: name
        val otherName = other.file?.name ?: other.name

        // First check whether the file is a directory (locals: `file` is a mutable @JvmField var, so a
        // re-read after the null check could still NPE - and smart-cast won't apply)
        val thisFile = file
        val otherFile = other.file
        if (thisFile != null && thisFile.isDirectory) {
            if (otherFile != null && !otherFile.isDirectory) {
                // Directories sort before files.
                return -1
            }
        } else if (otherFile != null && otherFile.isDirectory) {
            // Files sort after directories.
            return 1
        }

        return compareChar(thisName, otherName)
    }

    override fun toString(): String {
        return "FileItemBean{" +
                "file=" + file +
                ", name='" + name + '\'' +
                '}'
    }
}
