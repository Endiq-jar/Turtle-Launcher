package com.endiq.turtlelauncher.feature.skin

import android.util.Base64
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.value.MinecraftAccount
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TurtleLauncher: local skin/cape system.
 *
 * The problem: SkinCapeDialog/SkinLoader only ever saved a skin/cape PNG for this
 * launcher's OWN avatar UI (account picker face icon) - the actual Minecraft game
 * session never knew that file existed. For a real (Microsoft) account, Mojang's own
 * session server already supplies skin/cape data, so this only matters for LOCAL
 * (offline) accounts, which otherwise get the default Steve/Alex skin every time.
 *
 * The fix follows the same mechanism this launcher already uses for third-party
 * accounts (see BattlyAuthlibManager, and the generic authlib-injector path in
 * LaunchArgs.kt): authlib-injector is a real, widely-used, MIT-licensed Java agent
 * (yushijinhun/authlib-injector) that patches Minecraft's authlib at runtime to send
 * every session/profile lookup to a custom Yggdrasil-API-compatible server instead of
 * Mojang's. This class IS that server - a tiny embedded HTTP server, run inside this
 * launcher's own process, that answers those lookups using the skin/cape PNGs already
 * saved by SkinCapeDialog under PathManager.DIR_USER_SKIN.
 *
 * This covers "client-side" fully and unconditionally: the local player sees their own
 * skin/cape (menus, inventory, third-person, singleplayer, LAN), using the real
 * Minecraft skin/cape texture format (64x64 skin incl. modern slim/classic overlay
 * layout, 64x32 cape) - nothing about the PNGs themselves needs to change, only how
 * the game discovers their URL.
 *
 * "Server-side" (other players on a multiplayer server seeing the skin too) is only
 * possible if that server's own process *also* points authlib-injector at this same
 * API root - out of this launcher's control for any server it doesn't run. What this
 * class CAN do, honestly: bind to every network interface (not just loopback) when
 * [AllSettings.localSkinServerLanVisible] is on, so a small self-hosted server on the
 * same LAN/Wi-Fi can be pointed at this device's real address and pick up the same
 * skin data. See [lanInstructions]. Real Microsoft accounts never touch this class -
 * they already carry their skin server-side automatically through Mojang.
 */
object TurtleSkinServer {
    private const val TAG = "TurtleSkinServer"
    private const val API_PREFIX = "/api/yggdrasil"

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private var boundLan = false

    @Volatile private var port: Int = -1

    private val keyPair by lazy {
        // TurtleLauncher: generated once per launcher process and never persisted to
        // disk - authlib-injector only needs the public key to be internally
        // consistent with whatever private key actually signs each response, not to
        // match anything external, so a fresh in-memory keypair per session is both
        // simpler and safer than managing a long-lived private key file.
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    }
    private val privateKey: PrivateKey get() = keyPair.private
    private val publicKey: PublicKey get() = keyPair.public

    /**
     * Starts the server if not already running (idempotent - safe to call on every
     * launch). Restarts it if the LAN-visibility setting changed since it was last
     * started, since that changes which address it needs to bind to. Returns the
     * bound port, or -1 if the server could not start (best-effort: a failure here
     * should never block the game from launching - see LaunchArgs.kt's call site).
     */
    @Synchronized
    fun ensureStarted(lanVisible: Boolean): Int {
        if (running.get() && boundLan == lanVisible) return port

        stop()

        return try {
            val socket = ServerSocket(0, 50, if (lanVisible) null else java.net.InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            port = socket.localPort
            boundLan = lanVisible
            running.set(true)
            executor.submit { acceptLoop(socket) }
            Logging.i(TAG, "Local skin server listening on ${if (lanVisible) "0.0.0.0" else "127.0.0.1"}:$port")
            port
        } catch (e: Exception) {
            Logging.e(TAG, "Could not start local skin server", e)
            running.set(false)
            -1
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = -1
    }

    /** The authlib-injector API root to hand to the javaagent, e.g. "http://127.0.0.1:41231/api/yggdrasil". */
    fun apiRootUrl(): String = "http://127.0.0.1:$port$API_PREFIX"

    /** Best-effort LAN IPv4 address of this device, for the "point your server here" instructions. */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress
    }.getOrNull()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) Logging.e(TAG, "Accept failed", e)
                return
            }
            executor.submit { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 8000
                val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                // Drain headers - we don't need any of them for this minimal server.
                var line: String?
                do { line = input.readLine() } while (!line.isNullOrEmpty())

                val parts = requestLine.split(" ")
                val path = if (parts.size >= 2) parts[1].substringBefore("?") else "/"
                route(path, sock)
            } catch (e: Exception) {
                Logging.e(TAG, "Error handling skin server request", e)
            }
        }
    }

    private fun route(path: String, sock: Socket) {
        when {
            path == API_PREFIX || path == "$API_PREFIX/" -> writeJson(sock, 200, rootMetadata())
            path.startsWith("$API_PREFIX/sessionserver/session/minecraft/profile/") ->
                handleProfileRequest(sock, path.substringAfterLast("/"))
            path.startsWith("/textures/skin/") -> handleTexture(sock, textureFile(path.substringAfterLast("/"), cape = false))
            path.startsWith("/textures/cape/") -> handleTexture(sock, textureFile(path.substringAfterLast("/"), cape = true))
            else -> writeJson(sock, 404, JSONObject().put("error", "Not Found"))
        }
    }

    private fun handleProfileRequest(sock: Socket, rawUuid: String) {
        val uuid = rawUuid.replace("-", "").lowercase(Locale.ROOT)
        val account = findLocalAccountByUuid(uuid)
        if (account == null) {
            writeJson(sock, 204, null)
            return
        }
        writeJson(sock, 200, buildProfileResponse(account))
    }

    private fun handleTexture(sock: Socket, file: File?) {
        if (file == null || !file.isFile) {
            writeRaw(sock, 404, "text/plain", ByteArray(0))
            return
        }
        writeRaw(sock, 200, "image/png", file.readBytes())
    }

    private fun textureFile(rawUuid: String, cape: Boolean): File? {
        val account = findLocalAccountByUuid(rawUuid.replace("-", "").lowercase(Locale.ROOT)) ?: return null
        val suffix = if (cape) "_cape.png" else ".png"
        val file = File(PathManager.DIR_USER_SKIN, account.uniqueUUID + suffix)
        return file.takeIf { it.isFile }
    }

    /**
     * The UUID authlib-injector asks about is whatever this launcher sent the game as
     * auth_uuid, i.e. [MinecraftAccount.profileId] - NOT the separate uniqueUUID field
     * used to key the actual skin/cape PNG filenames. Both are stable per-account (see
     * MinecraftAccount.generateOfflineUUID for why profileId is now stable too), so
     * this lookup just needs to match one to the other.
     */
    private fun findLocalAccountByUuid(noDashUuid: String): MinecraftAccount? {
        return runCatching {
            com.endiq.turtlelauncher.feature.accounts.AccountsManager.allAccounts.firstOrNull { acc ->
                acc.profileId.replace("-", "").lowercase(Locale.ROOT) == noDashUuid
            }
        }.getOrNull()
    }

    private fun buildProfileResponse(account: MinecraftAccount): JSONObject {
        val noDashUuid = account.profileId.replace("-", "").lowercase(Locale.ROOT)
        val skinFile = File(PathManager.DIR_USER_SKIN, account.uniqueUUID + ".png")
        val capeFile = File(PathManager.DIR_USER_SKIN, account.uniqueUUID + "_cape.png")

        val textures = JSONObject()
        if (skinFile.isFile) {
            val skin = JSONObject().put("url", "http://127.0.0.1:$port/textures/skin/$noDashUuid")
            textures.put("SKIN", skin)
        }
        if (capeFile.isFile) {
            textures.put("CAPE", JSONObject().put("url", "http://127.0.0.1:$port/textures/cape/$noDashUuid"))
        }

        val texturesPayload = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("profileId", noDashUuid)
            .put("profileName", account.username)
            .put("textures", textures)

        val value = Base64.encodeToString(texturesPayload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val signature = sign(value)

        val property = JSONObject()
            .put("name", "textures")
            .put("value", value)
            .put("signature", signature)

        return JSONObject()
            .put("id", noDashUuid)
            .put("name", account.username)
            .put("properties", JSONArray().put(property))
    }

    private fun sign(value: String): String = try {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(privateKey)
        signer.update(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    } catch (e: Exception) {
        Logging.e(TAG, "Failed to sign skin texture payload", e)
        ""
    }

    private fun rootMetadata(): JSONObject {
        val meta = JSONObject()
            .put("serverName", "TurtleLauncher Local Skin Server")
            .put("implementationName", "turtle-skin-server")
            .put("implementationVersion", "1.0")
            .put("feature.non_email_login", true)
            .put("feature.enable_mojang_anti_features", false)
            .put("feature.username_check", false)
            .put("feature.no_mojang_namespace", true)

        val domains = JSONArray().put("127.0.0.1")
        if (boundLan) lanAddress()?.let { domains.put(it) }

        return JSONObject()
            .put("meta", meta)
            .put("skinDomains", domains)
            .put("signaturePublickey", pemPublicKey(publicKey))
    }

    /**
     * authlib-injector's metadata parser expects "signaturePublickey" as a full PEM
     * string (BEGIN/END markers + 64-char-wrapped base64 body), not a bare base64
     * blob - a raw blob fails with "Bad signature public key" even though the
     * underlying X.509 bytes are perfectly valid. See real-world server responses
     * from Blessing Skin / LittleSkin for the expected shape.
     */
    private fun pemPublicKey(key: PublicKey): String {
        val body = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    private fun writeJson(sock: Socket, status: Int, body: JSONObject?) {
        val bytes = (body?.toString() ?: "").toByteArray(Charsets.UTF_8)
        writeRaw(sock, status, "application/json; charset=utf-8", bytes)
    }

    private fun writeRaw(sock: Socket, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Error"
        }
        val out = sock.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    /** Human-readable setup text for whoever runs a multiplayer server and wants skins to show there too. */
    fun lanInstructions(): String {
        val addr = lanAddress() ?: "<this device's LAN IP>"
        return "-javaagent:authlib-injector.jar=http://$addr:$port$API_PREFIX"
    }
}
