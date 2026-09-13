package com.endiq.turtlelauncher.feature.turtle

import com.endiq.turtlelauncher.feature.log.Logging
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TurtleLauncher v10: background TCP-connect latency probe for the "Ping" HUD module.
 * Minecraft doesn't expose a raw ICMP ping on Android without root, so this measures
 * TCP handshake time against the current server's host:port instead — a good proxy for
 * in-game latency and requires no special permissions.
 */
object PingTracker {
    private const val TAG = "PingTracker"
    private const val TIMEOUT_MS = 1500
    private const val INTERVAL_MS = 2000L

    @Volatile private var running = false
    private val lastPingMs = AtomicInteger(-1)
    private val currentTarget = AtomicReference<Pair<String, Int>?>(null)
    private var worker: Thread? = null

    /** Call when the player connects to / switches servers. host may be "host:port" or bare host (defaults 25565). */
    @JvmStatic
    fun setTarget(address: String?) {
        if (address.isNullOrBlank()) {
            currentTarget.set(null)
            lastPingMs.set(-1)
            return
        }
        val parts = address.trim().split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 25565
        currentTarget.set(host to port)
    }

    @JvmStatic
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "TurtlePingTracker", isDaemon = true) {
            while (running) {
                val target = currentTarget.get()
                if (target == null) {
                    lastPingMs.set(-1)
                } else {
                    lastPingMs.set(measure(target.first, target.second))
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    @JvmStatic
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /** Returns last measured round-trip in ms, or -1 if unknown/unreachable. */
    @JvmStatic
    fun getPingMs(): Int = lastPingMs.get()

    private fun measure(host: String, port: Int): Int {
        return try {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            }
            ((System.nanoTime() - start) / 1_000_000L).toInt()
        } catch (e: Exception) {
            Logging.i(TAG, "Ping failed for $host:$port — ${e.message}")
            -1
        }
    }
}
