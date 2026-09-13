package com.endiq.turtlelauncher.utils.path

import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.InfoDistributor
import com.endiq.turtlelauncher.feature.log.Logging
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit

class UrlManager {
    companion object {
        private const val URL_USER_AGENT: String = "${InfoDistributor.LAUNCHER_NAME}/${BuildConfig.VERSION_NAME}"
        @JvmField
        val TIME_OUT = Pair(15000, TimeUnit.MILLISECONDS)
        const val URL_GITHUB_HOME: String = "https://api.github.com/repos/TurtleLauncher/Turtle-Info/contents/"
        const val URL_MCMOD: String = "https://www.mcmod.cn/"
        const val URL_MINECRAFT: String = "https://www.minecraft.net/"
        const val URL_MINECRAFT_VERSION_REPOS: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val URL_SUPPORT: String = "https://afdian.com/a/MovTery"
        const val URL_HOME: String = "https://github.com/Endiq-jar/TurtleLauncher"
        const val URL_FCL_RENDERER_PLUGIN: String = "https://github.com/ShirosakiMio/FCLRendererPlugin/releases/tag/Renderer"
        const val URL_FCL_DRIVER_PLUGIN: String = "https://github.com/FCL-Team/FCLDriverPlugin/releases/tag/Turnip"

        // TurtleLauncher: the whole app shares one OkHttpClient (including its connection
        // pool, thread pool and disk cache),
        // instead of every call site creating its own client. The shared pool reuses TCP/TLS
        // connections and cuts handshake overhead,
        // Fewer idle threads, and unchanged requests (version manifests, mod info) hit the
        // disk cache directly.
        // instead of hitting the network every time - this is exactly what "optimized downloads"
        // and "low battery usage" refer to.
        private val sharedClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .callTimeout(TIME_OUT.first.toLong(), TIME_OUT.second)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .dns(CustomDns)

            // PathManager.DIR_CACHE is a lateinit var; very early accesses could theoretically
            // touching an uninitialised lateinit property would throw UninitializedPropertyAccessException,
            // On failure just skip the disk cache; the rest of the networking keeps working.
            runCatching {
                val cacheDir = File(PathManager.DIR_CACHE, "http_cache")
                builder.cache(Cache(cacheDir, 20L * 1024 * 1024)) // 20 MB disk cache
            }.onFailure { e -> Logging.e("UrlManager", "Failed to set up HTTP disk cache", e) }

            builder.build()
        }

        @JvmStatic
        fun createConnection(url: URL): URLConnection {
            val connection = url.openConnection()
            connection.setRequestProperty("User-Agent", URL_USER_AGENT)
            connection.setConnectTimeout(TIME_OUT.first)
            connection.setReadTimeout(TIME_OUT.first)

            return connection
        }

        @JvmStatic
        @Throws(IOException::class)
        fun createHttpConnection(url: URL): HttpURLConnection {
            return createConnection(url) as HttpURLConnection
        }

        @JvmStatic
        fun createRequestBuilder(url: String): Request.Builder {
            return createRequestBuilder(url, null)
        }

        @JvmStatic
        fun createRequestBuilder(url: String, body: RequestBody?): Request.Builder {
            val request = Request.Builder().url(url).header("User-Agent", URL_USER_AGENT)
            body?.let{ request.post(it) }
            return request
        }

        // Return the shared client itself (shared pool/cache) instead of building a brand new
        // OkHttpClient every time.
        @JvmStatic
        fun createOkHttpClient(): OkHttpClient = sharedClient

        /**
         * Derive a customisable Builder from the shared OkHttpClient (the underlying connection
         * pool and disk cache are still shared),
         * instead of creating a fully independent client from scratch.
         */
        @JvmStatic
        fun createOkHttpClientBuilder(action: (OkHttpClient.Builder) -> Unit = { }): OkHttpClient.Builder {
            return sharedClient.newBuilder()
                .apply(action)
        }
    }
}