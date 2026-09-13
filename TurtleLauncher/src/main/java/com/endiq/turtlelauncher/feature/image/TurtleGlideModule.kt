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

        // The lower the memory tier, the smaller the image cache, so the launcher does not
        // compete with the game on low-end devices.
        // The disk cache is capped at 50 MB across all tiers so it does not eat storage.
        val (memoryCacheMb, bitmapPoolMb, diskCacheMb) = when {
            totalMemMb < 3 * 1024 -> Triple(16, 16, 30)   // <3 GB: low-memory device, save every MB
            totalMemMb < 6 * 1024 -> Triple(32, 32, 50)  // 3-6 GB: mainstream device
            else -> Triple(64, 64, 50)                   // 6 GB+: plenty of RAM, aggressive memory cache, disk cache still capped at 50 MB
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
            // Most launcher UI art needs no alpha channel; RGB_565 halves bitmap memory, which
            // helps a lot on low-end devices.
            .setDefaultRequestOptions(
                com.bumptech.glide.request.RequestOptions().format(DecodeFormat.PREFER_RGB_565)
            )
    }

    override fun registerComponents(context: Context, glide: com.bumptech.glide.Glide, registry: Registry) {
        // No extra components to parse; the default network/local decoding stack is enough.
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
