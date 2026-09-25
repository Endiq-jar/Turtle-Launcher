package kr.co.donghyun.flamelauncher.data.auth

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Stores a validated local skin for the offline account flow. */
object LocalSkinManager {
    private const val PREFS = "local_skin"
    private const val KEY_PATH = "path"
    private const val FILE_NAME = "skin.png"

    fun skinFile(context: Context): File = File(context.filesDir, "turtle-skin/$FILE_NAME")

    fun hasSkin(context: Context): Boolean = skinFile(context).isFile && skinFile(context).length() > 0

    /** Copies only a 64x64 or legacy 64x32 Minecraft PNG; rejects arbitrary large images. */
    fun importSkin(context: Context, uri: Uri): Boolean {
        val destination = skinFile(context)
        return try {
            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output, bufferSize = 16 * 1024) }
            } ?: return false
            if (destination.length() > 2 * 1024 * 1024L) return false.also { destination.delete() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destination.absolutePath, options)
            if (options.outWidth !in setOf(64) || options.outHeight !in setOf(32, 64)) {
                destination.delete()
                return false
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PATH, destination.absolutePath).apply()
            true
        } catch (_: Exception) {
            destination.delete()
            false
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PATH).apply()
        skinFile(context).delete()
    }
}
