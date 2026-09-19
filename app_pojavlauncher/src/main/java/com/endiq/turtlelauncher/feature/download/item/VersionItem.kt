package com.endiq.turtlelauncher.feature.download.item

import com.endiq.turtlelauncher.feature.download.enums.VersionType
import java.util.Date

/**
 * Version info class.
 * @param projectId unique id of the project this version belongs to
 * @param title title of the version
 * @param downloadCount total downloads of this version
 * @param uploadDate upload date of this version
 * @param mcVersions MC versions of this version
 * @param versionType release state of this version
 * @param fileName file name of the version
 * @param fileHash hash of the version file
 * @param fileUrl download link of the version file
 */
open class VersionItem(
    val projectId: String,
    val title: String,
    val downloadCount: Long,
    val uploadDate: Date,
    val mcVersions: List<String>,
    val versionType: VersionType,
    val fileName: String,
    val fileHash: String?,
    val fileUrl: String
) {
    override fun toString(): String {
        return "VersionItem(" +
                "projectId='$projectId', " +
                "title='$title', " +
                "downloadCount=$downloadCount, " +
                "uploadDate=$uploadDate, " +
                "mcVersions=$mcVersions, " +
                "versionType=$versionType, " +
                "fileName='$fileName'" +
                "fileHash='$fileHash'" +
                "fileUrl='$fileUrl'" +
                ")"
    }
}