package kr.co.donghyun.turtlelauncher.launch

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import kr.co.donghyun.turtlelauncher.data.jvm.JvmSettingsManager
import kr.co.donghyun.turtlelauncher.data.renderer.Renderer
import kr.co.donghyun.turtlelauncher.data.renderer.RendererManager

/**
 * Turtle's automatic launch tuning, adapted to the renamed launcher's settings/repositories.
 * It runs before a game starts, never changes the launcher layout, and leaves a manual renderer
 * choice alone when the user disabled automatic selection.
 */
object AutoSettingsOptimizer {
    private const val TAG = "AutoSettingsOptimizer"
    private const val PREFS = "turtle_auto_optimizer"
    private const val LOW_RAM_MB = 3072
    private const val HIGH_RAM_MB = 6144

    fun apply(context: Context, mcVersionId: String = "") {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val autoRenderer = prefs.getBoolean("auto_renderer", true)
        if (autoRenderer) selectRenderer(context, mcVersionId)

        val fastBoot = prefs.getBoolean("fast_boot", true)
        if (fastBoot && mcVersionId.isNotBlank() && prefs.getString("last_version", null) == mcVersionId) {
            Log.i(TAG, "Fast Boot: performance tier already applied for $mcVersionId")
            return
        }

        val totalRamMb = totalRamMb(context)
        val powerSaving = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
        val thermalThrottled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }.getOrDefault(false)

        val current = JvmSettingsManager.load(context)
        val tuned = when {
            thermalThrottled -> current.copy(
                resolutionScalePercent = 70,
                unlockFps = false,
                renderDistance = minOf(current.renderDistance, 4),
                graphicsMode = 0,
                disableClouds = true,
            )
            totalRamMb < LOW_RAM_MB -> current.copy(
                resolutionScalePercent = minOf(current.resolutionScalePercent, 80),
                unlockFps = false,
                renderDistance = minOf(current.renderDistance, 4),
                graphicsMode = 0,
                disableClouds = true,
            )
            totalRamMb < HIGH_RAM_MB || powerSaving -> current.copy(
                resolutionScalePercent = 100,
                unlockFps = false,
                renderDistance = minOf(current.renderDistance, 6),
                graphicsMode = 0,
                disableClouds = true,
            )
            else -> current.copy(
                resolutionScalePercent = 100,
                unlockFps = true,
                renderDistance = maxOf(current.renderDistance, 6).coerceAtMost(12),
                graphicsMode = 0,
                disableClouds = true,
            )
        }
        JvmSettingsManager.save(context, tuned)
        prefs.edit().putString("last_version", mcVersionId).apply()
        Log.i(TAG, "Applied ${totalRamMb}MB performance tier (powerSave=$powerSaving, thermal=$thermalThrottled)")
    }

    /** The old renderer policy's practical equivalent for the renderers bundled by Turtle. */
    private fun selectRenderer(context: Context, versionId: String) {
        if (versionId.isNotBlank() && isLegacy(versionId)) {
            RendererManager.save(context, Renderer.GL4ES)
            return
        }
        val gpu = buildString {
            append(Build.HARDWARE).append(' ').append(Build.MANUFACTURER).append(' ')
            append(Build.SOC_MANUFACTURER)
        }.lowercase()
        val vulkan = context.packageManager.hasSystemFeature("android.hardware.vulkan.level") ||
            context.packageManager.hasSystemFeature("android.hardware.vulkan.version")
        val renderer = when {
            gpu.contains("adreno") && vulkan -> Renderer.ZINK
            gpu.contains("mali") && vulkan -> Renderer.ZINK
            gpu.contains("powervr") && vulkan -> Renderer.ZINK
            gpu.contains("apple") && vulkan -> Renderer.ZINK
            else -> RendererManager.load(context)
        }
        RendererManager.save(context, renderer)
        Log.i(TAG, "Auto renderer=${renderer.id}, gpu=$gpu, vulkan=$vulkan")
    }

    private fun totalRamMb(context: Context): Int {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(1)
    }

    private fun isLegacy(version: String): Boolean {
        val major = Regex("^1\\.(\\d+)").find(version)?.groupValues?.get(1)?.toIntOrNull()
        return major != null && major <= 12
    }
}
