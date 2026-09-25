package com.endiq.turtlelauncher.feature.turtle

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Process-level performance and diagnostics carried over from the original Turtle launcher.
 * These helpers are deliberately headless: they do not add UI or alter the original Turtle
 * home screen, but keep the launcher's warm-start, ANR and memory-pressure protections active.
 */
object TurtleRuntimeSupport {
    private const val TAG = "TurtleRuntimeSupport"
    private const val PREFS = "turtle_runtime_features"
    private const val HEARTBEAT_MS = 1_000L
    private const val MEMORY_TICK_MS = 5_000L
    private const val LOW_MEMORY_RATIO = 0.15
    private val started = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "TurtleWarmStart").apply {
            isDaemon = true
            priority = Process.THREAD_PRIORITY_BACKGROUND
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("anr_detector_enabled", true)) startAnrWatchdog(prefs)
        if (prefs.getBoolean("memory_monitor_enabled", true)) startMemoryMonitor(app)
        if (prefs.getBoolean("background_asset_prefetch", true)) prefetch(app)
        if (prefs.getBoolean("smart_warm_start", true)) warmStart(app)
    }

    private fun startAnrWatchdog(prefs: android.content.SharedPreferences) {
        val lastHeartbeat = AtomicLong(System.currentTimeMillis())
        val handler = Handler(Looper.getMainLooper())
        thread(name = "TurtleAnrWatchdog", isDaemon = true) {
            var reported = false
            while (true) {
                val postedAt = System.currentTimeMillis()
                handler.post { lastHeartbeat.set(System.currentTimeMillis()) }
                try { Thread.sleep(HEARTBEAT_MS) } catch (_: InterruptedException) { return@thread }
                val timeout = prefs.getLong("anr_timeout_ms", 5_000L).coerceIn(2_000L, 60_000L)
                val stale = System.currentTimeMillis() - lastHeartbeat.get()
                if (lastHeartbeat.get() < postedAt && stale >= timeout) {
                    if (!reported) {
                        reported = true
                        val trace = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                        Log.e(TAG, "Main thread unresponsive for ${stale}ms:\n$trace")
                    }
                } else reported = false
            }
        }
    }

    private fun startMemoryMonitor(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val total = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.totalMem
        thread(name = "TurtleMemoryPressure", isDaemon = true) {
            var lastWarning = 0L
            while (true) {
                val info = ActivityManager.MemoryInfo()
                runCatching { manager.getMemoryInfo(info) }
                val ratio = if (total > 0) info.availMem.toDouble() / total else 1.0
                val now = System.currentTimeMillis()
                if ((info.lowMemory || ratio < LOW_MEMORY_RATIO) && now - lastWarning > 30_000L) {
                    lastWarning = now
                    Log.w(TAG, "Memory pressure: ${info.availMem / (1024 * 1024)}MB available " +
                        "(${(ratio * 100).toInt()}% of ${total / (1024 * 1024)}MB)")
                }
                try { Thread.sleep(MEMORY_TICK_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    private fun prefetch(context: Context) {
        executor.execute {
            try {
                // Install/list control presets early so opening the first game does not pay the
                // asset and JSON parse cost on the UI thread.
                com.endiq.turtlelauncher.data.key.ControlPresetManager.ensureInstalled(context)
                warmAsset(context, "options.txt")
                warmAsset(context, "default.json")
                warmAsset(context, "turtle_control_presets/survival.json")
                File(context.filesDir, "instances").listFiles()?.forEach { instance ->
                    listOf("mods", "resourcepacks", "shaderpacks").forEach { File(instance, it).listFiles() }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "Asset prefetch skipped: ${t.message}")
            } finally { }
        }
    }

    private fun warmStart(context: Context) {
        executor.execute {
            try {
                // Page-cache warm the selected native renderer/JRE payloads when they already
                // exist. Nothing is copied or loaded here, so this cannot change renderer choice.
                File(context.filesDir, "natives").listFiles()?.filter { it.isFile }?.forEach(::warmRead)
                File(context.filesDir, "jre").walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }?.let(::warmRead)
            } catch (t: Throwable) {
                Log.i(TAG, "Warm start skipped: ${t.message}")
            } finally { }
        }
    }

    private fun warmAsset(context: Context, name: String) {
        runCatching { context.assets.open(name).use { it.read(ByteArray(1)) } }
    }

    private fun warmRead(file: File) {
        runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (input.read(buffer) >= 0) { }
            }
        }
    }
}
