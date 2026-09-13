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

        //TurtleLauncher: 整个App共用一个OkHttpClient（以及其内部的连接池/线程池/磁盘缓存），
        //而不是每个调用点各自new一个新的客户端。共享连接池可以复用TCP/TLS连接，减少握手开销，
        //减少多余的空闲线程，磁盘缓存也能让没有变化的请求(如版本清单、mod信息)直接命中缓存
        //而不必每次都重新走网络——这些直接对应"Optimized downloads"和"Low battery usage"。
        private val sharedClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .callTimeout(TIME_OUT.first.toLong(), TIME_OUT.second)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .dns(CustomDns)

            //PathManager.DIR_CACHE是个lateinit var，理论上在极早期被访问时可能还未初始化，
            //直接访问未初始化的lateinit属性会抛出UninitializedPropertyAccessException，
            //这里捕获后直接跳过磁盘缓存即可，不影响其他网络功能
            runCatching {
                val cacheDir = File(PathManager.DIR_CACHE, "http_cache")
                builder.cache(Cache(cacheDir, 20L * 1024 * 1024)) // 20MB磁盘缓存
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

        //返回共享客户端本身（共用连接池/缓存），而不是每次都构造一个全新的OkHttpClient
        @JvmStatic
        fun createOkHttpClient(): OkHttpClient = sharedClient

        /**
         * 基于共享的OkHttpClient派生一个可自定义的Builder（仍共用底层连接池与磁盘缓存），
         * 而不是从零创建一个完全独立的客户端
         */
        @JvmStatic
        fun createOkHttpClientBuilder(action: (OkHttpClient.Builder) -> Unit = { }): OkHttpClient.Builder {
            return sharedClient.newBuilder()
                .apply(action)
        }
    }
}