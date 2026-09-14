package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File

/**
 * Picks which file counts as "the latest log" for Share Logs / the built-in Log Viewer.
 *
 * Three real problems here, found from actual crash reports users uploaded:
 *
 * 1. [PathManager.DIR_LAUNCHER_LOG] holds two files that get rewritten on *every* launch,
 *    crash or not - [Logging]'s rotating `log1.txt`..`log10.txt` and [GameLogcat]'s rolling
 *    `session_logcat.txt`. The one file that matters when something crashes - `latestlog.txt`,
 *    written only by [net.kdt.pojavlaunch.PojavApplication]'s uncaught-exception handler and
 *    [NativeCrashCapture] - is usually the OLDEST file in that folder during ordinary use, so a
 *    plain "newest mtime wins" picker almost always surfaces the mundane always-on log instead.
 *
 * 2. `DIR_GAME_HOME/latestlog.txt` (the launcher's own env+JVM-arg dump, written by
 *    Logger.begin() right before the game process is handed off) does NOT capture anything
 *    from the actual game process onward - confirmed from real uploads that end at the last
 *    `JVMArg:` line every time, crash or no crash. It looks plausible as "the log" (it's named
 *    exactly that) but it can never contain the exception a crash actually needs, so it should
 *    be the lowest-priority candidate, not compete evenly with files that might.
 *
 * 3. The current Minecraft version's own `logs/latest.log` (real Log4j2 output) is where an
 *    in-game/mod exception actually lands, and is more useful than #2 whenever present.
 *
 * Priority: real crash report > Minecraft's own latest.log > the pre-launch env dump.
 */
object LatestLogResolver {
    private const val CRASH_LOG_NAME = "latestlog.txt"

    @JvmStatic
    fun resolveLatestLogFile(): File? {
        val launcherLogDir = File(PathManager.DIR_LAUNCHER_LOG)
        val crashLog = File(launcherLogDir, CRASH_LOG_NAME)
        if (crashLog.isFile) return crashLog

        val mcLatestLog = VersionsManager.getCurrentVersion()?.let { v ->
            File(v.getGameDir(), "logs/latest.log").takeIf { it.isFile && it.length() > 0 }
        }
        if (mcLatestLog != null) return mcLatestLog

        val gameEnvDump = File(PathManager.DIR_GAME_HOME, "latestlog.txt")
            .takeIf { it.isFile && it.length() > 0 }

        val newestLauncherFile = launcherLogDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile }
            ?.maxByOrNull { it.lastModified() }

        return listOfNotNull(gameEnvDump, newestLauncherFile).maxByOrNull { it.lastModified() }
    }
}
