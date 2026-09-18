package com.endiq.turtlelauncher.feature.turtle.cursor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File

object CustomCursorLoader {
    /** Extensions this loader knows how to decode, for upload/file-picker validation. */
    @JvmField
    val SUPPORTED_EXTENSIONS = arrayOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "cur", "ani")

    @JvmStatic
    fun load(context: Context, file: File?): Drawable {
        if (file == null || !file.exists()) return fallback(context)

        return runCatching {
            when (file.extension.lowercase()) {
                "ani" -> {
                    val frames = AniDecoder.decode(file.readBytes())
                    if (frames.isNullOrEmpty()) fallback(context) else AnimatedCursorDrawable(frames).apply { start() }
                }
                "cur" -> {
                    val frame = CurIcoDecoder.decode(file.readBytes())
                    if (frame == null) fallback(context) else AnimatedCursorDrawable(listOf(frame))
                }
                else -> loadRasterCursor(context, file) ?: fallback(context)
            }
        }.getOrElse { e ->
            Logging.e("CustomCursorLoader", "Failed to decode custom cursor ${file.name}", e)
            fallback(context)
        }
    }

    /** @return whether [file] is something this loader can turn into a cursor. */
    @JvmStatic
    fun isSupportedCursorFile(file: File?): Boolean {
        if (file == null || file.isDirectory) return false
        val ext = file.extension.lowercase()
        if (ext == "ani" || ext == "cur") return true
        return runCatching {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.path, options)
            options.outWidth != -1 || options.outHeight != -1
        }.getOrDefault(false)
    }

    /**
     * Raster cursor formats (png/jpg/webp/...). A user-picked photo can be tens of
     * megapixels - decoding it at full size just to draw a tiny cursor wastes tens
     * of MB and risks an OOM crash on low-RAM devices, so images larger than
     * [MAX_CURSOR_DIMENSION] are downsampled first (256px is far beyond any cursor
     * ever drawn on screen).
     */
    private const val MAX_CURSOR_DIMENSION = 256

    private fun loadRasterCursor(context: Context, file: File): Drawable? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_CURSOR_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_CURSOR_DIMENSION
        ) sampleSize *= 2
        if (sampleSize == 1) return Drawable.createFromPath(file.absolutePath)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, opts)?.let { BitmapDrawable(context.resources, it) }
    }

    private fun fallback(context: Context): Drawable =
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_mouse_pointer, context.theme)
            ?: ColorDrawable(Color.TRANSPARENT)
}
