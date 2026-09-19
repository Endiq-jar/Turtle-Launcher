package com.endiq.turtlelauncher.feature.turtle

import android.app.Activity
import android.graphics.Bitmap
import android.os.Environment
import android.widget.Toast
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    private val nameFormat = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US)

    @JvmStatic
    fun takeScreenshot(activity: Activity) {
        val surfaceView = findGlSurfaceView(activity.window.decorView)
        if (surfaceView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            takeViaPixelCopy(activity, surfaceView)
        } else {
            takeViaViewDraw(activity)
        }
    }

    /** Finds the first SurfaceView/TextureView in the tree — that's where Minecraft's GL output actually lives. */
    private fun findGlSurfaceView(root: android.view.View): android.view.SurfaceView? {
        if (root is android.view.SurfaceView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findGlSurfaceView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun takeViaPixelCopy(activity: Activity, surfaceView: android.view.SurfaceView) {
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(surfaceView, bitmap, { result ->
            if (result == android.view.PixelCopy.SUCCESS) {
                saveAndNotify(activity, bitmap)
            } else {
                Logging.e(TAG, "PixelCopy failed with result $result")
                bitmap.recycle()
                TaskExecutors.getAndroidUI().execute {
                    Toast.makeText(activity, "Screenshot failed", Toast.LENGTH_SHORT).show()
                }
            }
        }, android.os.Handler(activity.mainLooper))
    }

    private fun takeViaViewDraw(activity: Activity) {
        Task.runTask {
            runCatching {
                val decorView = activity.window.decorView
                val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                decorView.draw(canvas)
                bitmap
            }.onFailure { e -> Logging.e(TAG, "Screenshot fallback failed", e) }.getOrNull()
        }.ended(TaskExecutors.getAndroidUI()) { bitmap ->
            if (bitmap != null) saveAndNotify(activity, bitmap)
            else Toast.makeText(activity, "Screenshot failed", Toast.LENGTH_SHORT).show()
        }.onThrowable {
            Logging.e(TAG, "Screenshot task threw")
        }.execute()
    }

    private fun saveAndNotify(activity: Activity, bitmap: Bitmap) {
        Task.runTask {
            runCatching {
                val dir = File(
                    activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "screenshots"
                ).apply { mkdirs() }
                val file = File(dir, "turtle_${nameFormat.format(Date())}.png")
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                bitmap.recycle()
                file
            }.onFailure { e -> Logging.e(TAG, "Failed to save screenshot", e) }.getOrNull()
        }.ended(TaskExecutors.getAndroidUI()) { file ->
            val message = if (file != null) "Screenshot saved: ${file.name}" else "Screenshot failed"
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }.onThrowable {
            Logging.e(TAG, "Screenshot save task threw")
        }.execute()
    }
}
