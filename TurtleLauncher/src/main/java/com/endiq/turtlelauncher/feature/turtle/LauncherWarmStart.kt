package com.endiq.turtlelauncher.feature.turtle

import android.content.Context
import android.os.Process
import androidx.tracing.Trace
import com.endiq.turtlelauncher.feature.accounts.AccountUtils
import com.endiq.turtlelauncher.feature.accounts.AccountsManager
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.authenticator.listener.DoneListener
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TurtleLauncher: Smart Launcher Warm Start (Section 12 of Endiq's mega-spec). While the
 * user is sitting on the main menu, opportunistically does the parts of "getting ready to
 * launch" that don't require touching the game/JVM yet, for whatever version is currently
 * selected - VersionsManager.getCurrentVersion() IS the "predict next instance": it's the
 * literal, real version Play would launch right now, not a separate guess/heuristic.
 *
 * ARCHITECTURE-HONESTY NOTE on "Warm Java process": this app launches Minecraft's JVM
 * in-process via JNI (see JREUtils/LaunchGame), not as a forked process - there is no way
 * to have a second JVM "ready and waiting" ahead of time without contradicting how launch
 * actually works here. What this does instead is the closest real equivalent: read the
 * predicted runtime's libjvm.so bytes into the OS page cache ahead of time, so the actual
 * dlopen at launch hits a warm cache instead of cold disk I/O. Same technique for "preload
 * libraries" (the selected renderer's .so files) - readahead only, never an actual
 * dlopen/System.load outside the established launch order, since loading a native lib for
 * real out of sequence is exactly the kind of thing that has caused real native crashes in
 * this project before (see the SDL/libSDL3.so saga).
 *
 * "Cache authentication": proactively triggers the same silent MSA refresh path
 * (MicrosoftBackgroundLogin) that preLaunch() already calls for real, just earlier and
 * quietly - no toast/error UI here, since this is purely opportunistic and preLaunch()'s
 * own real check still runs (and surfaces anything the user needs to see) at actual launch
 * time regardless. "Cache assets" is AssetPrefetcher's job, extended there rather than
 * duplicated here.
 *
 * Best-effort and silent throughout, same pattern as AssetPrefetcher: any one piece
 * failing is skipped and logged quietly, never blocks another piece or surfaces to the UI.
 */
object LauncherWarmStart {
    private const val TAG = "LauncherWarmStart"
    private const val WARM_READ_CHUNK = 64 * 1024

    private val threadNumber = AtomicInteger(0)
    private val backgroundThreadFactory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "WarmStart-${threadNumber.incrementAndGet()}")
    }
    private val executor = Executors.newFixedThreadPool(2, backgroundThreadFactory)
    private val alreadyRunning = AtomicBoolean(false)

    @JvmStatic
    fun warmStart(context: Context) {
        if (!AllSettings.smartWarmStart.getValue()) return
        if (!alreadyRunning.compareAndSet(false, true)) return

        executor.execute {
            Trace.beginSection("LauncherWarmStart.warmStart")
            try {
                warmRendererLibraries()
                VersionsManager.getCurrentVersion()?.let { warmJavaRuntime(it) }
                warmAuthentication(context)
            } finally {
                Trace.endSection()
                alreadyRunning.set(false)
            }
        }
    }

    /** Page-cache-warms the currently selected renderer's main library plus anything
     *  it declares needing dlopen'd alongside it. */
    private fun warmRendererLibraries() {
        runCatching {
            if (!Renderers.isCurrentRendererValid()) return
            val renderer = Renderers.getCurrentRenderer()
            val libNames = mutableListOf(renderer.getRendererLibrary())
            runCatching { libNames.addAll(renderer.getDlopenLibrary().value) }
            libNames.forEach { libName ->
                if (libName.isNotBlank() && !libName.startsWith("/")) {
                    warmReadFile(File(PathManager.DIR_NATIVE_LIB, libName))
                }
            }
        }.onFailure { e -> Logging.i(TAG, "Renderer warm skipped: ${e.message}") }
    }

    /** Page-cache-warms the predicted version's already-assigned Java runtime's
     *  libjvm.so. Deliberately does not trigger a JRE auto-install if none is assigned
     *  yet - that's a real network/side-effect-heavy operation, not appropriate for a
     *  silent idle warm-up. */
    private fun warmJavaRuntime(version: com.endiq.turtlelauncher.feature.version.Version) {
        runCatching {
            val javaDir = version.getJavaDir()
            if (javaDir.isNullOrEmpty() || !javaDir.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX)) return
            val runtimeName = javaDir.removePrefix(Tools.LAUNCHERPROFILES_RTPREFIX)
            if (runtimeName.isEmpty()) return
            val home = MultiRTUtils.getRuntimeHome(runtimeName)
            if (!home.isDirectory) return
            // Exact lib/<arch>/server/libjvm.so subpath varies by runtime package - a
            // depth-limited search beats hardcoding a path and silently missing it.
            findFileByName(home, "libjvm.so", maxDepth = 5)?.let { warmReadFile(it) }
        }.onFailure { e -> Logging.i(TAG, "Runtime warm skipped: ${e.message}") }
    }

    /** Silent, best-effort proactive MSA token refresh - see class doc for why this is
     *  safe to fire-and-forget here. */
    private fun warmAuthentication(context: Context) {
        runCatching {
            val account = AccountsManager.currentAccount ?: return
            if (!AccountUtils.isMicrosoftAccount(account)) return
            AccountUtils.microsoftLogin(
                context,
                account,
                DoneListener { /* silent: preLaunch() re-checks for real at launch time */ },
                ErrorListener { e -> Logging.i(TAG, "Background auth warm skipped: ${e.message}") }
            )
        }.onFailure { e -> Logging.i(TAG, "Auth warm skipped: ${e.message}") }
    }

    private fun findFileByName(root: File, targetName: String, maxDepth: Int): File? {
        if (maxDepth < 0) return null
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (child.isFile && child.name == targetName) return child
        }
        for (child in children) {
            if (child.isDirectory) {
                findFileByName(child, targetName, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    private fun warmReadFile(file: File) {
        if (!file.isFile) return
        runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(WARM_READ_CHUNK)
                while (input.read(buffer) >= 0) { /* reading pages it into the OS cache */ }
            }
        }
    }
}
