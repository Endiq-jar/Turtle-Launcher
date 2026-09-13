package com.endiq.turtlelauncher.feature.shizuku

import com.endiq.turtlelauncher.feature.log.Logging
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TurtleLauncher: runs a shell command with Shizuku's privilege (root, or the ADB shell user).
 *
 * ### Why the reflection
 * `Shizuku.newProcess(String[], String[], String)` is the only part of the Shizuku API that
 * spawns a shell process, and in Shizuku-API 13.1.5 (the version this integrates against) it
 * is **private** - it was hidden ahead of its planned removal in API v14, while the returned
 * [rikka.shizuku.ShizukuRemoteProcess] type stayed public. It is therefore not reachable at
 * compile time, so it is called reflectively here and typed as a plain [Process].
 *
 * That is deliberate rather than a shortcut: the documented, future-proof route is a Shizuku
 * "user service" (bindUserService + a generated AIDL Stub), which is a much larger surface -
 * an .aidl source set, a generated interface, a manifest entry and asynchronous bind
 * lifecycle - for what is here a handful of short-lived commands. Keeping the risky part in
 * one small, loudly-commented function means that if Shizuku removes the method outright,
 * the failure is contained to this file (every caller degrades to "unavailable" instead of
 * crashing), and the swap to a user service is a single-function change.
 *
 * Nothing here throws: a failed command is reported through [ShizukuShell.Result].
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"

    /** Outcome of one command. [ok] is a convenience for `exitCode == 0 && error.isBlank()`. */
    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val ran: Boolean
    ) {
        val ok: Boolean get() = ran && exitCode == 0

        /** Human-readable one-liner for the settings screen. */
        fun summary(): String = when {
            !ran -> "unavailable"
            ok -> "ok"
            else -> (stderr.ifBlank { stdout }).trim().take(200).ifBlank { "exit $exitCode" }
        }
    }

    private val UNAVAILABLE = Result("", "Shizuku is not available", -1, ran = false)

    /**
     * Runs [command] through `sh -c` with Shizuku's privilege and waits up to
     * [timeoutSeconds] for it to finish.
     *
     * Must be called off the main thread (it blocks).
     */
    @JvmOverloads
    fun run(command: String, timeoutSeconds: Long = 15): Result {
        if (!ShizukuManager.isReady) return UNAVAILABLE

        val process = newRemoteProcess(arrayOf("sh", "-c", command))
            ?: return Result("", "This Shizuku version does not expose a process API", -1, ran = false)

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outReader = Thread({
            runCatching { process.inputStream.bufferedReader().useLines { it.forEach { line -> stdout.appendLine(line) } } }
        }, "turtle-shizuku-out").apply { isDaemon = true; start() }

        val errReader = Thread({
            runCatching { process.errorStream.bufferedReader().useLines { it.forEach { line -> stderr.appendLine(line) } } }
        }, "turtle-shizuku-err").apply { isDaemon = true; start() }

        val exitCode = try {
            // waitFor() alone has no timeout in the remote-process implementation, so the
            // deadline is enforced by a separate latch. On timeout we destroy() the process,
            // which closes its pipes and so releases the two reader threads above.
            val finished = CountDownLatch(1)
            val waiter = Thread({
                runCatching { process.waitFor() }
                finished.countDown()
            }, "turtle-shizuku-wait").apply { isDaemon = true; start() }

            val completed = finished.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                runCatching { process.destroy() }
                Logging.w(TAG, "Command timed out after ${timeoutSeconds}s: $command")
            }
            outReader.join(2_000)
            errReader.join(2_000)
            if (!completed) -1 else runCatching { process.exitValue() }.getOrDefault(0)
        } catch (t: Throwable) {
            Logging.w(TAG, "Failed to run command: $command", t)
            -1
        } finally {
            runCatching { process.destroy() }
        }

        return Result(stdout.toString(), stderr.toString(), exitCode, ran = true)
    }

    /**
     * Reflective call to the private `Shizuku.newProcess(...)`; see the class doc.
     *
     * @return the remote process, or null if this Shizuku build cannot provide one.
     */
    private fun newRemoteProcess(cmd: Array<String>): Process? {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        }.onFailure {
            Logging.w(TAG, "Shizuku's process API is unavailable on this build", it)
        }.getOrNull()
    }
}
