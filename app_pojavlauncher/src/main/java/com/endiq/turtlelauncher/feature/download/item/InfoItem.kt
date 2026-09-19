package com.endiq.turtlelauncher.feature.download.item

import com.endiq.turtlelauncher.feature.download.enums.Category
import com.endiq.turtlelauncher.feature.download.enums.Classify
import com.endiq.turtlelauncher.feature.download.enums.Platform
import java.util.Date

/**
 * Base info class.
 * @param classify category of the project
 * @param platform platform hosting the project
 * @param projectId unique id of the project
 * @param slug slug of the project
 * @param author author of the project
 * @param title title of the project
 * @param description description of the project
 * @param downloadCount total downloads of the project
 * @param uploadDate upload date of the project
 * @param iconUrl cover image link of the project
 * @param category tags of the project
 */
open class InfoItem(
    val classify: Classify,
    val platform: Platform,
    val projectId: String,
    val slug: String,
    val author: Array<String>?,
    val title: String,
    val description: String,
    val downloadCount: Long,
    val uploadDate: Date,
    val iconUrl: String?,
    val category: List<Category>
) {
    fun copy() = InfoItem(
        classify, platform, projectId, slug, author, title, description, downloadCount, uploadDate, iconUrl, category
    )

    override fun toString(): String {
        return "InfoItem(" +
                "classify='$classify', " +
                "platform='$platform', " +
                "projectId='$projectId', " +
                "slug='$slug', " +
                "author=${author.contentToString()}, " +
                "title='$title', " +
                "description='$description', " +
                "downloadCount=$downloadCount, " +
                "uploadDate=$uploadDate, " +
                "iconUrl='$iconUrl', " +
                "category=$category" +
                ")"
    }
}