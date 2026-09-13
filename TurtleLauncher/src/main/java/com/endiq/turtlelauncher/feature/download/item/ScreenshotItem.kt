package com.endiq.turtlelauncher.feature.download.item

/**
 * Screenshot info record.
 * @param imageUrl address of the screenshot
 * @param title title of the screenshot
 * @param description description of the screenshot
 */
class ScreenshotItem(
    val imageUrl: String,
    val title: String?,
    val description: String?
) {
    override fun toString(): String {
        return "ScreenshotItem(imageUrl='$imageUrl', title='$title', description='$description')"
    }
}