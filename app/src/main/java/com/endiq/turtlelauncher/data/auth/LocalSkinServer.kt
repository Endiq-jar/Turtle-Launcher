package com.endiq.turtlelauncher.data.auth

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback Yggdrasil skin endpoint used only for explicit offline accounts.
 * Authlib Injector reads the signed textures property and the game never needs
 * an internet-facing skin service.
 */
object LocalSkinServer {
    private const val API = "/api/yggdrasil"
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null
    private var port = -1
    private val keyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair() }

    @Synchronized
    fun ensureStarted(context: Context, account: LocalAccount): Int {
        if (running.get()) return port
        if (!LocalSkinManager.hasSkin(context)) return -1
        return runCatching {
            val bound = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
            socket = bound
            port = bound.localPort
            running.set(true)
            executor.submit { acceptLoop(context, account, bound) }
            port
        }.getOrElse { -1 }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        port = -1
    }

    private fun acceptLoop(context: Context, account: LocalAccount, server: ServerSocket) {
        while (running.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: return
            executor.submit { handle(context, account, client) }
        }
    }

    private fun handle(context: Context, account: LocalAccount, client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request = reader.readLine() ?: return
                while (!reader.readLine().isNullOrEmpty()) Unit
                val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                when {
                    path == API || path == "$API/" -> write(socket, 200, metadata())
                    path.startsWith("$API/sessionserver/session/minecraft/profile/") ->
                        write(socket, 200, profile(account))
                    path == "/textures/skin/${account.uuid.replace("-", "").lowercase(Locale.ROOT)}" ->
                        writeBytes(socket, 200, "image/png", LocalSkinManager.skinFile(context).readBytes())
                    else -> write(socket, 404, JSONObject().put("error", "Not Found"))
                }
            }
        }
    }

    private fun metadata(): JSONObject = JSONObject()
        .put("meta", JSONObject().put("serverName", "TurtleLauncher local skin server").put("implementationName", "TurtleLauncher"))
        .put("skinDomains", JSONArray().put("127.0.0.1"))
        .put("signaturePublickey", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))

    private fun profile(account: LocalAccount): JSONObject {
        val uuid = account.uuid.replace("-", "").lowercase(Locale.ROOT)
        val textures = JSONObject().put("SKIN", JSONObject().put("url", "http://127.0.0.1:$port/textures/skin/$uuid"))
        val payload = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("profileId", uuid)
            .put("profileName", account.username)
            .put("textures", textures)
            .toString()
        val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val signer = Signature.getInstance("SHA1withRSA").apply {
            initSign(keyPair.private)
            update(encoded.toByteArray(Charsets.UTF_8))
        }
        return JSONObject()
            .put("id", uuid)
            .put("name", account.username)
            .put("properties", JSONArray().put(JSONObject()
                .put("name", "textures")
                .put("value", encoded)
                .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP))))
    }

    private fun write(socket: Socket, status: Int, json: JSONObject) =
        writeBytes(socket, status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private fun writeBytes(socket: Socket, status: Int, type: String, body: ByteArray) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }
}
