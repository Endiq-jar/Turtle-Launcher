package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * TurtleLauncher: system-side (logcat) capture of a game session.
 *
 * Why this exists: every crash screen in this launcher reads its raw log from
 * `latestlog.txt`, which is written by the NATIVE side (`net.endiq.launcher.Logger
 * .appendToLog`) from inside the JVM that is running Minecraft. When the game dies the way
 * it most often dies on real devices - a SIGSEGV/SIGABRT in the renderer, an OOM kill, an
 * ANR kill - that process is gone instantly. Whatever was still sitting in the native
 * logger's buffer is gone with it, so `latestlog.txt` is frequently EMPTY (or truncated
 * mid-line) at exactly the moment you need it. That is the "logs didn't appear" half of the
 * support reports: the crash screen opened, but there was nothing in it.
 *
 * logcat does not have that problem. It is a kernel buffer owned by logd, not by the dying
 * process, so everything the game (and the launcher, and the renderer) printed survives the
 * crash and can be read afterwards. An app is always allowed to read its OWN log lines
 * (everything sharing its UID - which here means the `:launcher`, `:game` and
 * `:gui_installer` processes), with no permission needed. `READ_LOGS` would be required to
 * read other apps' lines, and nothing here needs that.
 *
 * So this runs `logcat` as a live drain into a rolling file for the duration of a session,
 * and offers a synchronous one-shot dump for when a session is already over. Both are
 * cross-process safe: the file lives under [PathManager.DIR_LAUNCHER_LOG], which every
 * process of this app resolves to the same path, so the `:game` process can write it and
 * ErrorActivity (which runs in the `:launcher` process) can read it back.
 *
 * This is deliberately NOT a replacement for CrashAnalyzer/ErrorActivity - it is a
 * *fallback source of raw log text* for them, used only when `latestlog.txt` has nothing.
 */
object GameLogcat {
    private const val TAG = "GameLogcat"

    /** Rolling capture file. Capped at [MAX_BYTES]; when it overflows it restarts from
     *  empty so the newest output is always what survives, not the oldest. */
    private const val FILE_NAME = "session_logcat.txt"

    /** 1 MiB is plenty of logcat for diagnosis and small enough to read back quickly on
     *  the low-end devices this launcher targets. */
    private const val MAX_BYTES = 1024 * 1024L

    /** Hard cap on how much of the file we hand back to a crash screen. */
    private const val MAX_RETURNED_CHARS = 64 * 1024

    /** How long a one-shot `logcat -d` is allowed to take before we give up on it. */
    private const val DUMP_TIMEOUT_SECONDS = 6L

    private val lock = Any()

    @Volatile
    private var drainProcess: Process? = null

    @Volatile
    private var drainThread: Thread? = null

    /**
     * Starts draining logcat into the rolling session file. Call as close as possible to
     * the JVM launch so the earliest (and usually most useful) startup output is captured.
     *
     * Safe to call when a session is already running - the previous drain is stopped first.
     * Safe to call on a device where `logcat` cannot be spawned: it logs and gives up, and
     * every read path below simply sees an empty file.
     */
    @JvmStatic
    fun start() {
        stop()
        val dir = runCatching { File(PathManager.DIR_LAUNCHER_LOG) }.getOrNull() ?: return
        if (!dir.exists() && !dir.mkdirs()) {
            Logging.w(TAG, "Could not create $dir - logcat capture disabled for this session")
            return
        }
        val file = File(dir, FILE_NAME)
        val process = try {
            // -v threadtime keeps timestamps + PID/TID, which is what makes the trace
            // readable once lines from several processes are interleaved.
            Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime"))
        } catch (t: Throwable) {
            Logging.w(TAG, "Could not spawn logcat - logcat capture disabled for this session", t)
            return
        }

        val thread = Thread({
            drainLoop(process, file)
        }, "turtle-logcat-drain")
        thread.isDaemon = true

        synchronized(lock) {
            drainProcess = process
            drainThread = thread
        }
        thread.start()
        Logging.i(TAG, "Logcat capture started -> ${file.absolutePath}")
    }

    /** Stops the live drain (if any) and releases the logcat process. */
    @JvmStatic
    fun stop() {
        val process: Process?
        val thread: Thread?
        synchronized(lock) {
            process = drainProcess
            thread = drainThread
            drainProcess = null
            drainThread = null
        }
        runCatching { process?.destroy() }
        thread?.let {
            // destroy() closes the pipes, which is what unblocks the blocking read() below;
            // interrupt() only helps if the thread is between reads.
            it.interrupt()
        }
    }

    private fun drainLoop(process: Process, file: File) {
        var written = 0L
        try {
            FileOutputStream(file, false).use { out ->
                process.inputStream.buffered(8192).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written >= MAX_BYTES) {
                            // Ring-ish: keep the NEWEST output rather than growing forever.
                            out.flush()
                            out.channel.truncate(0)
                            out.channel.position(0)
                            written = 0
                        }
                    }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            // A drain thread dying must never affect the game. Interruption on stop() is
            // expected and not worth logging as an error.
            if (t !is InterruptedException) Logging.w(TAG, "Logcat drain stopped", t)
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * @return the tail of the live session capture, or "" if there is nothing usable.
     * This is the fallback ErrorActivity uses when `latestlog.txt` came back empty.
     */
    @JvmStatic
    @JvmOverloads
    fun readSessionTail(maxChars: Int = MAX_RETURNED_CHARS): String {
        val file = runCatching { File(PathManager.DIR_LAUNCHER_LOG, FILE_NAME) }.getOrNull()
            ?: return ""
        if (!file.isFile || file.length() == 0L) return ""
        return runCatching {
            val text = file.readText()
            if (text.length <= maxChars) text else text.substring(text.length - maxChars)
        }.getOrDefault("")
    }

    /**
     * One-shot `logcat -d` dump, for when no live session was ever started (e.g. the crash
     * was detected on the NEXT app launch, long after the drain thread died with the game).
     *
     * @param maxLines how many of the most recent lines to ask logcat for.
     */
    @JvmStatic
    @JvmOverloads
    fun dumpNow(maxLines: Int = 4000): String {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", maxLines.toString()))
        } catch (t: Throwable) {
            Logging.w(TAG, "Could not run logcat -d", t)
            return ""
        }
        return try {
            // `logcat -d` normally exits as soon as it has dumped, but a wedged logd (or a
            // very large -t on a busy device) must never be able to hang a crash screen.
            // A watchdog destroys the process if it overruns; destroying it closes the pipe
            // and so unblocks the reader below.
            val watchdog = Thread({
                runCatching {
                    if (!process.waitFor(DUMP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroy()
                }
            }, "turtle-logcat-watchdog")
            watchdog.isDaemon = true
            watchdog.start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            watchdog.interrupt()
            text
        } catch (t: Throwable) {
            Logging.w(TAG, "Failed to read logcat -d output", t)
            ""
        } finally {
            runCatching { process.destroy() }
        }
    }

    /** @return a human-readable header for a crash report, or "" when there is no capture. */
    @JvmStatic
    fun formatForReport(rawLogcat: String): String {
        if (rawLogcat.isBlank()) return ""
        return String.format(
            Locale.ROOT,
            "──── system logcat (fallback - latestlog.txt was empty) ────%n%s",
            rawLogcat
        )
    }
}
