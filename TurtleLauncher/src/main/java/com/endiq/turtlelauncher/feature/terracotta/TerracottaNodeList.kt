package com.endiq.turtlelauncher.feature.terracotta

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.path.UrlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TerracottaNodeList {
    private const val TAG = "TerracottaNodeList"

    /**
     * Remotely updatable lists, tried in order, first one that returns anything wins.
     *
     * The first two are this repo's own [terracotta-nodes.json](../terracotta-nodes.json)
     * (raw.githubusercontent + a jsDelivr mirror, so one CDN being blocked or having a stale
     * cache isn't fatal). Being able to change the relay list without shipping an APK is the
     * whole point: every node below will eventually die, and the previous behaviour -
     * hardcoded addresses baked into a native .so we can't rebuild - meant a dead node was
     * a dead feature until someone rebuilt four ABI variants of the library.
     *
     * The third is FoldCraftLauncher's original list, kept for continuity.
     */
    private val REMOTE_SOURCES = listOf(
        "https://raw.githubusercontent.com/Endiq-jar/TurtleLauncher/main/terracotta-nodes.json",
        "https://cdn.jsdelivr.net/gh/Endiq-jar/TurtleLauncher@main/terracotta-nodes.json",
        "https://terracotta.glavo.site/nodes",
    )

    private val PINNED_FALLBACK = listOf(
        "tcp://public.easytier.cn:11010",   // EasyTier's own documented shared node
        "tcp://161.33.207.13:51010",        // community public relay (EasyTier discussion #2429)
        "udp://161.33.207.13:51010",        // same relay, UDP transport
        "udp://public.easytier.cn:11010",
        "ws://161.33.207.13:51011",         // same relay over WebSocket (restrictive networks)
        "quic://161.33.207.13:51012",
        "tcp://public.easytier.top:11010",  // Terracotta built-in, kept in case it returns
        "tcp://public2.easytier.cn:54321",
        "https://etnode.zkitefly.eu.org/node1",
        "https://etnode.zkitefly.eu.org/node2",
    )

    /**
     * EasyTier is handed every URI we give it and dials them all. A long dead tail mostly
     * buys connection timeouts, and the guest only has 15 seconds to find the host, so cap
     * the list to the best few after ranking.
     */
    private const val MAX_NODES = 6

    /** Only the head of the list is worth spending probe time on - the tail is dead-ish by
     *  definition and keeps its canonical order either way. */
    private const val MAX_PROBE_TARGETS = 8

    private const val PROBE_TIMEOUT_MS = 1200
    private const val CACHE_FILE = "terracotta_nodes.json"

    private val DEFAULT_PORTS = mapOf(
        "tcp" to 11010, "udp" to 11010, "faketcp" to 11010,
        "ws" to 11011, "wss" to 11012, "wg" to 11011, "quic" to 11012,
        "http" to 80, "https" to 443,
    )

    /** Sized to run the whole probe set in one round - this sits directly in front of
     *  host/join, so wall-clock time here is time the user stares at a spinner. */
    private val probePool = Executors.newFixedThreadPool(MAX_PROBE_TARGETS) { runnable ->
        Thread(runnable, "TerracottaNodeProbe").apply { isDaemon = true }
    }

    @Volatile
    private var cached: List<String>? = null

    /** Fetches (and caches for the process lifetime) the node list. Blocking. */
    @JvmStatic
    fun fetch(): List<String> {
        cached?.takeIf { it.isNotEmpty() }?.let { return it }

        synchronized(this) {
            cached?.takeIf { it.isNotEmpty() }?.let { return it }

            val custom = customOverride()
            if (custom != null) {
                cached = custom
                return custom
            }

            val canonical = LinkedHashSet<String>()
            for (url in REMOTE_SOURCES) {
                val remote = fetchRemote(url)
                if (remote.isNotEmpty()) {
                    canonical.addAll(remote)
                    break
                }
            }
            if (canonical.isEmpty()) canonical.addAll(readCache())
            PINNED_FALLBACK.forEach { canonical.add(it) }

            val ordered = canonical.toList()
            persist(ordered)

            val resolved = probeAll(ordered).take(MAX_NODES)
            if (resolved.isNotEmpty()) cached = resolved
            return resolved
        }
    }

    /** Call after changing enableTerracottaNodes/terracottaNodes so the next host/join
     *  picks up the new value instead of the process-lifetime cache. */
    @JvmStatic
    fun invalidateCache() {
        cached = null
    }

    data class Status(val primary: String?, val reachable: Int, val total: Int, val usingCustom: Boolean)

    /** Probes the current list for the UI. Runs in parallel; safe to call from the main
     *  thread - the blocking work is dispatched to IO. */
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val custom = customOverride()
        if (custom != null) return@withContext Status(custom.firstOrNull(), 1, 1, true)
        runCatching {
            val list = fetch()
            Status(list.firstOrNull(), probeMap(list).count { it == Reach.YES }, list.size, false)
        }.getOrDefault(Status(PINNED_FALLBACK.first(), 0, PINNED_FALLBACK.size, false))
    }

    // ============================ Resolution ============================

    private fun customOverride(): List<String>? {
        if (!AllSettings.enableTerracottaNodes.getValue()) return null
        val custom = AllSettings.terracottaNodes.getValue().trim()
        return if (custom.isEmpty()) null else listOf(custom)
    }

    private fun fetchRemote(url: String): List<String> = runCatching {
        val client = UrlManager.createOkHttpClientBuilder {
            it.callTimeout(8, TimeUnit.SECONDS)
        }.build()
        client.newCall(UrlManager.createRequestBuilder(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            parseNodes(response.body?.string().orEmpty())
        }
    }.onFailure { e ->
        Logging.w(TAG, "Failed to fetch node list from $url", e)
    }.getOrDefault(emptyList())

    private fun parseNodes(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.get("nodes")
                ?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            else -> return emptyList()
        }

        val out = ArrayList<String>()
        for (element: JsonElement in array) {
            val url: String?
            val region: String?
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    url = element.asString
                    region = null
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    url = obj.get("url")
                        ?.takeIf { it.isJsonPrimitive && !it.isJsonNull && it.asJsonPrimitive.isString }
                        ?.asString
                    region = obj.get("region")
                        ?.takeIf { it.isJsonPrimitive && !it.isJsonNull && it.asJsonPrimitive.isString }
                        ?.asString
                }
                else -> continue
            }
            if (url.isNullOrBlank()) continue
            if (!shouldUseNode(region)) continue
            out.add(url.trim())
        }
        return out
    }

    private fun shouldUseNode(region: String?): Boolean {
        if (region.isNullOrBlank()) return true
        // Region-tagged nodes are restricted to devices in that region (only "CN" tags
        // exist in the wild today).
        return !region.equals("CN", ignoreCase = true) || isChinaMainland()
    }

    /** Ported from Zalith Launcher 2 (LocalUtils.isChinaMainland): timezone-based on
     *  purpose - the in-app language is user-settable and says nothing about where the
     *  device actually is. */
    private fun isChinaMainland(): Boolean {
        val timeZone = TimeZone.getDefault()
        if (timeZone.id in listOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Urumqi")) return true

        val offsetMillis = timeZone.getOffset(System.currentTimeMillis())
        val isUtcPlus8 = offsetMillis == 8 * 60 * 60 * 1000
        if (!isUtcPlus8) return false

        // UTC+8 alone is ambiguous (Singapore, Perth, …) and the app locale can be
        // changed in-app, so - matching Zalith - don't claim mainland China from it.
        return false
    }

    private fun cacheFile(): File? =
        runCatching { File(PathManager.DIR_DATA, CACHE_FILE) }.getOrNull()

    private fun persist(nodes: List<String>) {
        val file = cacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JsonArray().apply { nodes.forEach { add(it) } }.toString())
        }.onFailure { e -> Logging.w(TAG, "Failed to persist node list", e) }
    }

    private fun readCache(): List<String> {
        val file = cacheFile() ?: return emptyList()
        return runCatching {
            if (!file.isFile) return@runCatching emptyList()
            JsonParser.parseString(file.readText()).asJsonArray.mapNotNull {
                runCatching { it.asString }.getOrNull()?.takeIf { s -> s.isNotBlank() }
            }
        }.onFailure { e -> Logging.w(TAG, "Failed to read cached node list", e) }
            .getOrDefault(emptyList())
    }

    // ============================== Probing ==============================

    private enum class Reach { YES, UNKNOWN, NO }

    /** Re-ranks live-first. Never drops anything - a node we can't probe (or that our probe
     *  can't reach but EasyTier can, e.g. behind a VPN) stays in the tail. */
    private fun probeAll(nodes: List<String>): List<String> {
        if (nodes.size <= 1) return nodes
        return runCatching {
            val ranked = probeMap(nodes)
            nodes.mapIndexed { index, node -> Triple(index, node, ranked[index]) }
                .sortedWith(compareBy<Triple<Int, String, Reach>> { it.third }.thenBy { it.first })
                .map { it.second }
        }.onFailure { e -> Logging.w(TAG, "Node probing failed", e) }
            .getOrDefault(nodes)
    }

    private fun probeMap(nodes: List<String>): List<Reach> {
        val results = arrayOfNulls<Reach>(nodes.size)
        val probeCount = minOf(nodes.size, MAX_PROBE_TARGETS)

        val futures = (0 until probeCount).map { index ->
            probePool.submit {
                results[index] = runCatching { probeOne(nodes[index]) }.getOrDefault(Reach.UNKNOWN)
            }
        }
        futures.forEach { runCatching { it.get(4, TimeUnit.SECONDS) } }
        return nodes.indices.map { results[it] ?: Reach.UNKNOWN }
    }

    private fun probeOne(node: String): Reach {
        val parsed = runCatching { URI(node) }.getOrNull() ?: return Reach.NO
        val scheme = parsed.scheme?.lowercase() ?: return Reach.NO
        val host = parsed.host ?: return Reach.NO
        val port = parsed.port.takeIf { it > 0 } ?: DEFAULT_PORTS[scheme] ?: return Reach.NO

        return when (scheme) {
            "tcp" -> if (tcpOpen(host, port)) Reach.YES else Reach.NO
            "http", "https" -> if (httpAlive(node)) Reach.YES else Reach.NO
            else -> Reach.UNKNOWN
        }
    }

    private fun tcpOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private fun httpAlive(url: String): Boolean = runCatching {
        val client = UrlManager.createOkHttpClientBuilder {
            it.connectTimeout(2, TimeUnit.SECONDS)
            it.callTimeout(3, TimeUnit.SECONDS)
        }.build()
        client.newCall(UrlManager.createRequestBuilder(url).build()).execute().use { response ->
            response.isSuccessful && response.body?.string().orEmpty().isNotBlank()
        }
    }.getOrDefault(false)
}
