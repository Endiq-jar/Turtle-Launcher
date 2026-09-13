package com.endiq.turtlelauncher.utils

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * TurtleLauncher: Zstandard-backed compression for launcher-owned disk caches.
 *
 * com.github.luben:zstd-jni was already a declared dependency (real prebuilt native libs for
 * all 4 Android ABIs, confirmed via web search before relying on it) but had never actually been
 * wired into anything - this is that wiring, finally used for real.
 *
 * Only use this for files this codebase both writes AND reads itself (e.g. the mod-info parse
 * cache) - never for anything Minecraft, a mod loader, or another app reads directly, since this
 * changes the on-disk format from plain text to a Zstd frame.
 *
 * Safe to introduce on top of an existing plain-text cache with no migration step: a pre-update
 * plain-JSON file fed to [readCompressed] won't match Zstd's frame magic and will throw, so the
 * caller's existing "cache load failed, treat as empty/cold" fallback naturally handles it - the
 * cache just rebuilds once, in the new compressed format, on the first run after updating.
 */
object CacheCompression {
    @JvmStatic
    fun writeCompressed(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        ZstdOutputStream(FileOutputStream(file)).use { zos ->
            zos.write(text.toByteArray(charset))
        }
    }

    @JvmStatic
    fun readCompressed(file: File, charset: Charset = Charsets.UTF_8): String {
        return ZstdInputStream(FileInputStream(file)).use { zis ->
            zis.readBytes().toString(charset)
        }
    }
}
