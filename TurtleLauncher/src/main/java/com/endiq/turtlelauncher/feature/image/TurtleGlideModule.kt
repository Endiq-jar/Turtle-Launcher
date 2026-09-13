package com.endiq.turtlelauncher.feature.image

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.module.AppGlideModule
import com.endiq.turtlelauncher.utils.platform.MemoryUtils

/**
 * TurtleLauncher: tunes Glide's memory/bitmap-pool/disk-cache sizes based on the
 * device's total RAM (reusing the same RAM-tier logic as the Auto Settings Optimizer),
 * instead of relying purely on Glide's own heuristics. Low-RAM devices get smaller
 * caches (less background memory pressure while the game itself is running),
 * higher-RAM devices get more headroom for smoother, snappier image loading
 * (skins, mod icons, screenshots, etc).
 */
@GlideModule
class TurtleGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val totalMemMb = MemoryUtils.getTotalDeviceMemory(context) / (1024 * 1024)

        //内存等级越低，给图片缓存分配的空间越小，避免在低内存设备上和游戏本体抢内存
        //磁盘缓存统一封顶50MB，不需要随内存等级继续增大，避免占用太多存储空间
        val (memoryCacheMb, bitmapPoolMb, diskCacheMb) = when {
            totalMemMb < 3 * 1024 -> Triple(16, 16, 30)   // <3GB: 低内存设备，尽量节省
            totalMemMb < 6 * 1024 -> Triple(32, 32, 50)  // 3-6GB: 主流设备
            else -> Triple(64, 64, 50)                   // 6GB+: 高内存设备，内存缓存更激进，磁盘缓存仍封顶50MB
        }

        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(1.5f)
            .setBitmapPoolScreens(1.5f)
            .build()

        val memoryCacheSize = minOf(calculator.memoryCacheSize.toLong(), memoryCacheMb * 1024L * 1024L)
        val bitmapPoolSize = minOf(calculator.bitmapPoolSize.toLong(), bitmapPoolMb * 1024L * 1024L)

        builder
            .setMemoryCache(LruResourceCache(memoryCacheSize))
            .setBitmapPool(LruBitmapPool(bitmapPoolSize))
            .setDiskCache(InternalCacheDiskCacheFactory(context, diskCacheMb * 1024L * 1024L))
            //大部分启动器UI素材不需要透明位深，RGB_565减半位图内存占用，对省内存帮助很大
            .setDefaultRequestOptions(
                com.bumptech.glide.request.RequestOptions().format(DecodeFormat.PREFER_RGB_565)
            )
    }

    override fun registerComponents(context: Context, glide: com.bumptech.glide.Glide, registry: Registry) {
        //不需要解析任何额外组件，使用默认的网络/本地解码栈即可
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
