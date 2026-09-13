package com.endiq.turtlelauncher.feature.version.utils

import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.feature.version.VersionsManager
import org.apache.commons.io.FileUtils

/**
 * Sets version icons automatically, or resets a custom icon.
 */
class VersionIconUtils(
    private val version: Version
) {
    private val iconFile = VersionsManager.getVersionIconFile(version)

    /**
     * Resolve the default icon of a version (vanilla block, mod-loader cover, ...); a custom
     * icon always wins.
     * @return whether a custom icon is set, so the reset action knows what to do
     */
    fun start(imageView: ImageView): Boolean {
        val context = imageView.context

        var isIconSet = false
        var isCustomIcon = false

        iconFile.let { icon ->
            if (icon.exists()) {
                Glide.with(imageView)
                    .load(icon)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .into(imageView)
                isIconSet = true
                isCustomIcon = true
            }
        }

        version.getVersionInfo()?.let { versionInfo ->
            versionInfo.loaderInfo?.forEach { loaderInfo ->
                if (!isIconSet) {
                    getLoaderIcon(loaderInfo.name)?.let { icon ->
                        imageView.setImageDrawable(ContextCompat.getDrawable(context, icon))
                        isIconSet = true
                    }
                } else return@forEach
            }
        }

        if (!isIconSet) imageView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_minecraft))

        return isCustomIcon
    }

    /**
     * Reset by deleting the custom icon file.
     * **This operation is irreversible.**
     */
    fun resetIcon() {
        FileUtils.deleteQuietly(iconFile)
    }

    /**
     * @return the cover icon of the current version
     */
    fun getIconFile() = iconFile

    private fun getLoaderIcon(name: String): Int? {
        return when(name.lowercase()) {
            "fabric" -> R.drawable.ic_fabric
            "forge" -> R.drawable.ic_anvil
            "quilt" -> R.drawable.ic_quilt
            "neoforge" -> R.drawable.ic_neoforge
            "optifine" -> R.drawable.ic_optifine
            "liteloader" -> R.drawable.ic_chicken_old
            else -> null
        }
    }
}