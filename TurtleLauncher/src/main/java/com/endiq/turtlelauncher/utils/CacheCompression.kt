package com.endiq.turtlelauncher.utils

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset

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
