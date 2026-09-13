package com.endiq.turtlelauncher.feature.terracotta.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.accounts.AccountsManager
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.terracotta.Terracotta
import com.endiq.turtlelauncher.feature.terracotta.TerracottaState
import com.endiq.turtlelauncher.ui.subassembly.aichat.ChatMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Chat piggybacked on Terracotta's P2P connection. Terracotta/EasyTier itself has no chat
 * feature - it only establishes a virtual LAN between host and guest(s) (see
 * [net.burningtnt.terracotta.TerracottaAndroidAPI] and the VpnService route setup in
 * [com.endiq.turtlelauncher.feature.terracotta.TerracottaVpnService]) and hands back a
 * server address to join in Minecraft. Everything below is a small custom protocol layered
 * on top of that tunnel, once it's up:
 *
 * - Host: plain [ServerSocket] on [CHAT_PORT], one thread per connected guest, star
 *   topology - broadcasts every line to all *other* connected guests and surfaces it
 *   locally, same as a message the host itself sends.
 * - Guest: connects out to the host's virtual IP - taken from [TerracottaState.GuestOK.getUrl],
 *   which is the same host:port string FCL's own UI hands the user to paste into Minecraft's
 *   "Add Server" screen (see MultiplayerDialog.GuestOkUI in FCL-Team/FoldCraftLauncher), so
 *   the host portion is already known to be reachable over the tunnel - on [CHAT_PORT].
 * - Wire format: newline-delimited JSON, `{"sender":"name","text":"..."}` per line, UTF-8.
 *
 * Lifecycle is driven entirely by [Terracotta]'s own state listener - start() connects/listens
 * when the state becomes [TerracottaState.HostOK] / [TerracottaState.GuestOK], stop() tears
 * everything down on any other state (back to waiting, or an exception).
 */
object TerracottaChat {
    /** Arbitrary high port, distinct from any Minecraft server port Terracotta might expose,
     *  just needs to be the same fixed value on both ends of the tunnel. */
    private const val CHAT_PORT = 34199

    fun interface Listener {
        fun onMessage(message: ChatMessage)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var guestSocket: Socket? = null
    private val hostClientWriters = CopyOnWriteArrayList<OutputStream>()

    @Volatile private var running = false

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    private val stateListener = Terracotta.StateListener { state ->
        when (state) {
            is TerracottaState.HostOK -> startHost()
            is TerracottaState.GuestOK -> startGuest(state)
            else -> stop()
        }
    }

    /** Call once (idempotent) before relying on chat - typically alongside
     *  Terracotta.initialize(). Safe to call from any thread. */
    @Synchronized
    fun attach() {
        Terracotta.removeStateListener(stateListener) // avoid double-registration
        Terracotta.addStateListener(stateListener)
    }

    /** True once a chat transport (host or guest) is actually connected. */
    val isActive: Boolean get() = running

    /** Sends a chat message as the local player. No-op if chat isn't connected. */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !running) return

        val sender = AccountsManager.currentAccount?.username ?: "Player"
        val line = JsonObject().apply {
            addProperty("sender", sender)
            addProperty("text", trimmed)
        }.toString() + "\n"

        when (Terracotta.getMode()) {
            Terracotta.TerracottaMode.HOST -> executor.execute { broadcastFromHost(line, exclude = null) }
            Terracotta.TerracottaMode.GUEST -> executor.execute { writeLine(guestSocket, line) }
            null -> Unit
        }

        // The sender doesn't get their own message echoed back over the socket
        // (host doesn't send to itself, guest only reads what the host relays back
        // to *other* guests) - surface it locally right away either way.
        notifyListeners(ChatMessage(text = trimmed, isUser = true))
    }

    @Synchronized
    private fun startHost() {
        if (running && serverSocket != null) return
        stopInternal()

        running = true
        executor.execute {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(CHAT_PORT))
                serverSocket = socket

                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (t: Throwable) {
                        if (running) Logging.w("TerracottaChat", "Host accept loop stopped", t)
                        break
                    }
                    handleHostClient(client)
                }
            } catch (t: Throwable) {
                // TurtleLauncher: was catch (Exception) - same Error-vs-Exception gap as
                // Terracotta.java's poll daemon (see its comment). This thread pool has no
                // custom UncaughtExceptionHandler, so anything that slips past a narrower
                // catch here still takes the whole app down, not just this connection.
                Logging.w("TerracottaChat", "Failed to start chat host server", t)
                running = false
            }
        }
    }

    private fun handleHostClient(client: Socket) {
        hostClientWriters.add(client.getOutputStream())
        executor.execute {
            try {
                BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        val message = parseLine(line) ?: continue
                        notifyListeners(message)
                        // Relay to every other connected guest, not back to the sender.
                        broadcastFromHost(line + "\n", exclude = client.getOutputStream())
                    }
                }
            } catch (t: Throwable) {
                Logging.w("TerracottaChat", "Chat client connection dropped", t)
            } finally {
                hostClientWriters.remove(client.getOutputStream())
                runCatching { client.close() }
            }
        }
    }

    private fun broadcastFromHost(line: String, exclude: OutputStream?) {
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        for (writer in hostClientWriters) {
            if (writer === exclude) continue
            runCatching { writer.write(bytes); writer.flush() }
        }
    }

    @Synchronized
    private fun startGuest(state: TerracottaState.GuestOK) {
        if (running && guestSocket != null) return
        stopInternal()

        val host = state.url?.substringBeforeLast(':')
        if (host.isNullOrBlank()) {
            Logging.w("TerracottaChat", "GuestOK had no usable url for chat: ${state.url}")
            return
        }

        running = true
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, CHAT_PORT), 10_000)
                guestSocket = socket

                BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        val message = parseLine(line) ?: continue
                        notifyListeners(message)
                    }
                }
            } catch (t: Throwable) {
                if (running) Logging.w("TerracottaChat", "Guest chat connection failed", t)
            } finally {
                running = false
            }
        }
    }

    private fun writeLine(socket: Socket?, line: String) {
        val target = socket ?: return
        runCatching {
            target.getOutputStream().let { it.write(line.toByteArray(StandardCharsets.UTF_8)); it.flush() }
        }.onFailure { e -> Logging.w("TerracottaChat", "Failed to send chat message", e) }
    }

    private fun parseLine(line: String): ChatMessage? = runCatching {
        val obj = JsonParser.parseString(line).asJsonObject
        val sender = obj.get("sender")?.asString ?: "Player"
        val text = obj.get("text")?.asString ?: return null
        ChatMessage(text = "$sender: $text", isUser = false)
    }.getOrElse { e ->
        Logging.w("TerracottaChat", "Dropped malformed chat line", e)
        null
    }

    @Synchronized
    private fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { guestSocket?.close() }
        guestSocket = null
        hostClientWriters.clear()
    }

    private fun notifyListeners(message: ChatMessage) {
        for (listener in listeners) listener.onMessage(message)
    }
}
