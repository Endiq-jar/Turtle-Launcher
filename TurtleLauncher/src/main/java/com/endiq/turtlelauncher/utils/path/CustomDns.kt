package com.endiq.turtlelauncher.utils.path

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * TurtleLauncher: lets the user pick a public DNS resolver for the launcher's own
 * network requests (downloads/API calls), independent from the download-source
 * (BMCLAPI) mirror in [DownloadMirror] — that one swaps *which URL* is requested,
 * this one swaps *which server resolves the hostname*. Useful when a device/ISP's
 * default DNS blocks or mis-resolves Mojang/Microsoft/Modrinth/GitHub domains.
 *
 * Implemented as a minimal DNS-over-UDP (RFC 1035) client — a single A-record query
 * sent straight to the chosen resolver's IP on port 53 — rather than pulling in a full
 * DNS library. If anything goes wrong (timeout, malformed reply, no A record, etc.)
 * this transparently falls back to the system resolver, exactly like [DownloadMirror]
 * falls back to the official source when a mirror doesn't have a file.
 */
object CustomDns : Dns {

    private val SERVERS = mapOf(
        "quad9" to "9.9.9.9",
        "cloudflare" to "1.1.1.1",
        "google" to "8.8.8.8"
    )

    // Small in-memory cache so we don't re-query the resolver for every single
    // request to the same host within a launcher session.
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val QUERY_TIMEOUT_MS = 3000

    override fun lookup(hostname: String): List<InetAddress> {
        val serverKey = runCatching { AllSettings.dnsServer.getValue() }.getOrDefault("default")
        val serverIp = SERVERS[serverKey]
        if (serverIp == null) {
            // "default" (or an unrecognised value) → let the system resolver handle it.
            return Dns.SYSTEM.lookup(hostname)
        }

        cache[hostname]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAtMs) return entry.addresses
        }

        val resolved = runCatching { queryA(hostname, serverIp) }
            .onFailure { e -> Logging.w("CustomDns", "Query for $hostname via $serverKey ($serverIp) failed, falling back to system DNS", e) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return Dns.SYSTEM.lookup(hostname)

        cache[hostname] = CacheEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
        return resolved
    }

    /** Sends one A-record query for [hostname] to [serverIp]:53 and returns every address found. */
    private fun queryA(hostname: String, serverIp: String): List<InetAddress> {
        val transactionId = (Math.random() * 0xFFFF).toInt()
        val query = buildQuery(transactionId, hostname)

        DatagramSocket().use { socket ->
            socket.soTimeout = QUERY_TIMEOUT_MS
            val serverAddress = InetAddress.getByName(serverIp)
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(serverAddress, 53)))

            val buffer = ByteArray(512)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)

            return parseResponse(buffer, responsePacket.length, transactionId)
        }
    }

    private fun buildQuery(transactionId: Int, hostname: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Header: ID, flags (standard query, recursion desired), 1 question, 0/0/0 other sections
        out.write(transactionId ushr 8); out.write(transactionId and 0xFF)
        out.write(0x01); out.write(0x00) // flags = 0x0100 (RD=1)
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00) // ANCOUNT = 0
        out.write(0x00); out.write(0x00) // NSCOUNT = 0
        out.write(0x00); out.write(0x00) // ARCOUNT = 0

        // Question: QNAME (length-prefixed labels), QTYPE=A(1), QCLASS=IN(1)
        for (label in hostname.split(".")) {
            if (label.isEmpty()) continue
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00) // root terminator
        out.write(0x00); out.write(0x01) // QTYPE = A
        out.write(0x00); out.write(0x01) // QCLASS = IN

        return out.toByteArray()
    }

    private fun parseResponse(buffer: ByteArray, length: Int, expectedTransactionId: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val responseId = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        if (responseId != expectedTransactionId) return emptyList()

        val qdCount = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val anCount = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)

        var pos = 12
        // Skip the question section (we only ever send exactly one question).
        repeat(qdCount) { pos = skipName(buffer, pos) + 4 /* QTYPE + QCLASS */ }

        val results = mutableListOf<InetAddress>()
        repeat(anCount) {
            if (pos >= length) return@repeat
            pos = skipName(buffer, pos)
            if (pos + 10 > length) return@repeat
            val type = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            val rdLength = ((buffer[pos + 8].toInt() and 0xFF) shl 8) or (buffer[pos + 9].toInt() and 0xFF)
            pos += 10
            if (type == 1 && rdLength == 4 && pos + 4 <= length) { // TYPE A, 4-byte IPv4 address
                val addrBytes = buffer.copyOfRange(pos, pos + 4)
                runCatching { results.add(InetAddress.getByAddress(addrBytes)) }
            }
            pos += rdLength
        }
        return results
    }

    /** Advances past a (possibly compressed) DNS name, returning the new offset. */
    private fun skipName(buffer: ByteArray, start: Int): Int {
        var pos = start
        while (pos < buffer.size) {
            val len = buffer[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2 // compression pointer, 2 bytes total
            pos += 1 + len
        }
        return pos
    }
}
