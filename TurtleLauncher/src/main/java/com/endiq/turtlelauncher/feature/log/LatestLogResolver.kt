package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File

object LatestLogResolver {
    private const val CRASH_LOG_NAME = "latestlog.txt"

    /**
     * The file behind the home screen's "Last Game Log" card: the newest game-session log
     * that actually contains the game's own output. Two candidates:
     *  - the current version's `logs/latest.log` - Minecraft's own Log4j2 file, the fullest
     *    record of an in-game session;
     *  - `DIR_GAME_HOME/latestlog.txt` - the launcher's game-session log (pre-launch env
     *    dump +, since GameOutputCapture tees the game JVM's stdout/stderr into it, the
     *    game's console output as well).
     * Whichever was written most recently wins - that's the last game session. Null when no
     * game has ever produced a log yet.
     */
    @JvmStatic
    fun resolveLastGameLogFile(): File? {
        val candidates = mutableListOf<File>()
        runCatching {
            com.endiq.turtlelauncher.feature.version.VersionsManager.getCurrentVersion()?.let { version ->
                candidates += File(version.getGameDir(), "logs/latest.log")
            }
        }
        candidates += File(PathManager.DIR_GAME_HOME, "latestlog.txt")
        return candidates
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
    }

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
