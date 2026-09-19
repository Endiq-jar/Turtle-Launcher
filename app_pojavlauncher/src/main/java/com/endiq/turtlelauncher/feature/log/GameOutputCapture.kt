package com.endiq.turtlelauncher.feature.log

import java.io.PrintStream

object GameOutputCapture {

    private const val TAG = "GameOutputCapture"

    /** Guard against a pathological never-ending line growing the buffer without bound. */
    private const val MAX_PENDING_LINE = 16 * 1024

    @Volatile
    private var installed = false

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null

    /**
     * Starts teeing System.out/System.err into the game log file. Safe to call multiple
     * times - only the first call has any effect. Must be called before the game JVM
     * starts so the earliest startup output is captured.
     */
    @JvmStatic
    @Synchronized
    fun install() {
        if (installed) return
        originalOut = System.out
        originalErr = System.err
        System.setOut(TeePrintStream(originalOut!!))
        System.setErr(TeePrintStream(originalErr!!))
        installed = true
        Logging.i(TAG, "Game stdout/stderr capture installed -> Logger.appendToLog")
    }

    /** Restores the original System.out/System.err. No-op when not installed. */
    @JvmStatic
    @Synchronized
    fun uninstall() {
        if (!installed) return
        installed = false
        runCatching {
            originalOut?.let { System.setOut(it) }
            originalErr?.let { System.setErr(it) }
        }
        originalOut = null
        originalErr = null
    }

    /**
     * A PrintStream that forwards everything to [delegate] (the original stream) exactly as
     * before, and additionally mirrors what's written line-by-line into the native game log.
     * All print/println/printf paths funnel through the two write() overrides (PrintStream's
     * own text machinery writes back through them), so overriding both captures everything.
     */
    private class TeePrintStream(private val delegate: PrintStream) : PrintStream(delegate, true) {

        private val lineBuffer = StringBuilder()
        private val lock = Any()

        override fun write(oneByte: Int) {
            super.write(oneByte)
            synchronized(lock) { appendChar(oneByte.toChar()) }
        }

        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            super.write(buffer, offset, count)
            // A fresh String per chunk instead of per byte keeps multi-KB mod dumps cheap.
            synchronized(lock) {
                runCatching {
                    val text = String(buffer, offset, count, Charsets.UTF_8)
                    for (element in text) appendChar(element)
                }
            }
        }

        private fun appendChar(c: Char) {
            when (c) {
                '\n' -> emitLine()
                '\r' -> {
                    // Progress-style output (mod download bars etc.) rewrites one line with
                    // \r; treat it like a line break so the log stays readable.
                    emitLine()
                }
                else -> {
                    lineBuffer.append(c)
                    if (lineBuffer.length > MAX_PENDING_LINE) emitLine()
                }
            }
        }

        private fun emitLine() {
            if (lineBuffer.isEmpty()) return
            val line = lineBuffer.toString()
            lineBuffer.setLength(0)
            // appendToLog writes into latestlog.txt and notifies the in-game log viewer's
            // listener - one call, both destinations. Must never throw into the game.
            runCatching { net.kdt.pojavlaunch.Logger.appendToLog(line) }
                .onFailure { e -> Logging.w(TAG, "Could not append a game output line to the log", e) }
        }
    }
}
