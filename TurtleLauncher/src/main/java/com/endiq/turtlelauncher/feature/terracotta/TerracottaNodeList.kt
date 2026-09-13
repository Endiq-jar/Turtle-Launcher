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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The EasyTier rendezvous/relay nodes Terracotta is told to use when hosting or joining a
 * room. **This is the fix for the "Cannot find scaffolding server" / PingHostFail join
 * failure** - see the long comment on [PINNED_FALLBACK] for why the list matters at all.
 *
 * How Terracotta actually uses these, established by reading the native library we ship
 * (arm64-v8a/libterracotta.so, "Terracotta 0.4.2, EasyTier v2.5.0-terracotta.2") and its
 * upstream source (github.com/burningtnt/Terracotta):
 *
 * - `src/easytier/publics.rs` -> `fetch_public_nodes()` takes whatever we pass in and
 *   **appends** four hardcoded nodes to it. So these are extra nodes, not a replacement:
 *   the built-in dead ones are always there, and ours are the only healthy ones we control.
 * - `src/controller/rooms/scaffolding/room.rs` -> `start_guest()` starts EasyTier, then
 *   polls `easytier.get_players()` **5 times, 3 seconds apart (15s total)** looking for a
 *   peer whose hostname starts with `scaffolding-mc-server-`. If it doesn't see one in time
 *   it logs "Cannot find scaffolding server" and raises `ExceptionType::PingHostFail`.
 *
 * So joining has exactly two failure modes we can act on from Kotlin:
 *   1. the two sides never land in a common EasyTier network (no working shared node), or
 *   2. they do, but not within the native 15-second discovery window.
 *
 * This file addresses (1); TerracottaFragment's join-retry addresses (2).
 *
 * Blocking - call from a background thread.
 */
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

    /**
     * Always present, in this exact order, on every device - even with no network at all.
     *
     * Two peers only discover each other if their node lists share a node that actually
     * works. If the host's list and the guest's list are both "whatever the network gave us
     * just now", they can easily end up disjoint, and then no amount of retrying helps. This
     * fixed baseline is the overlap we can always guarantee.
     *
     * Why these particular addresses, and why they are here rather than trusting the native
     * library's own defaults (`src/easytier/publics.rs`):
     *   tcp://public.easytier.top:11010  - CNAMEs to public.easytier.cn, which currently has
     *                                      no A record. Dead.
     *   tcp://public2.easytier.cn:54321  - no A record. Dead.
     *   https://etnode.zkitefly.eu.org/node1 and /node2 - unresolved, assumed dead.
     *                                     (spelled out rather than globbed: Kotlin's block
     *                                     comments nest, so writing the wildcard here opened
     *                                     a comment that swallowed the rest of this file.)
     * i.e. most of the built-in bootstrap set is gone, which is the actual root cause: host
     * and guest never meet, the 15s window expires, PingHostFail.
     *
     * Everything below is a *guess that will rot* - public EasyTier nodes are donated by
     * volunteers and come and go. That is precisely why the app probes them at runtime and
     * re-ranks live-first, and why the remote file above exists: a dead entry degrades to
     * "sorted last", not "feature broken".
     */
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

            // A user-supplied node is used exclusively, matching upstream: if you've got your
            // own EasyTier server you don't want us silently joining public networks too.
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
            // Network down (or every source dead): fall back to the last list we managed to
            // fetch, so a transient outage doesn't silently downgrade to baseline-only.
            if (canonical.isEmpty()) canonical.addAll(readCache())
            PINNED_FALLBACK.forEach { canonical.add(it) }

            val ordered = canonical.toList()
            persist(ordered)

            // TurtleLauncher: the old code cached whatever came back - including the
            // emptyList() every network failure produces - for the entire process lifetime.
            // One dead fetch therefore poisoned every later host/join in that session, which
            // is a big part of why joining "just never works". Never cache empty.
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

    /** Tolerates both shapes: a bare JSON array, or `{"nodes": [...]}`. Entries may be
     *  plain strings or `{"url": "..."}` objects, so the FCL list and ours both parse. */
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
            val url = when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
                element.isJsonObject -> element.asJsonObject.get("url")
                    ?.takeIf { it.isJsonPrimitive && !it.isJsonNull && it.asJsonPrimitive.isString }
                    ?.asString
                else -> null
            }
            if (!url.isNullOrBlank()) out.add(url.trim())
        }
        return out
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
        // Bounded and short: this runs on the caller's thread immediately before host/join,
        // so it must never be able to hang the screen. All probes go out in parallel, and the
        // get() timeout is only a safety net over the per-probe socket timeout.
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
            // udp/ws/wss/quic/wg/faketcp can't be cheaply probed from Java without speaking
            // the protocol - a UDP "connect" always succeeds and proves nothing, and opening
            // a WebSocket by hand proves little about EasyTier's. Rank them below proven ones
            // rather than claiming they're down.
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
