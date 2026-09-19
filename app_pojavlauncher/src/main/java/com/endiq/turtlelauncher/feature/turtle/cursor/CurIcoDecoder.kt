package com.endiq.turtlelauncher.feature.turtle.cursor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CurIcoDecoder {
    private const val MAX_DIMENSION = 512 // sanity bound - real cursors are never larger

    /** @return the decoded frame, or null if [data] isn't a well-formed ICO/CUR image. */
    fun decode(data: ByteArray): CursorFrame? {
        if (data.size < 22) return null // ICONDIR(6) + at least one ICONDIRENTRY(16)

        return runCatching {
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            header.position(2)
            val type = header.short.toInt() // 1 = ICO, 2 = CUR
            val count = header.short.toInt()
            if (count <= 0) return null

            // First ICONDIRENTRY, starting right after the 6-byte ICONDIR.
            val widthByte = data[6].toInt() and 0xFF
            val heightByte = data[7].toInt() and 0xFF
            header.position(10)
            val hotspotXRaw = header.short.toInt() and 0xFFFF
            val hotspotYRaw = header.short.toInt() and 0xFFFF
            val bytesInRes = header.int
            val imageOffset = header.int

            if (bytesInRes <= 0 || imageOffset < 0 || imageOffset.toLong() + bytesInRes > data.size) {
                return null
            }

            val bitmap = if (isPngSignature(data, imageOffset)) {
                BitmapFactory.decodeByteArray(data, imageOffset, bytesInRes)
            } else {
                decodeDib(data, imageOffset, bytesInRes)
            } ?: return null

            // A cursor (type 2) carries a real authored hotspot; a plain icon (type 1)
            // fed through here (some .ani "icon" chunks are stored as ICO, not CUR) has
            // none, so fall back to dead center, which is the sanest default.
            val hotspotX = if (type == 2) hotspotXRaw else bitmap.width / 2
            val hotspotY = if (type == 2) hotspotYRaw else bitmap.height / 2

            CursorFrame(
                bitmap,
                hotspotX.coerceIn(0, bitmap.width),
                hotspotY.coerceIn(0, bitmap.height),
                0L
            )
        }.getOrNull()
    }

    private fun isPngSignature(data: ByteArray, offset: Int): Boolean {
        if (offset + 8 > data.size) return false
        return data[offset] == 0x89.toByte() &&
                data[offset + 1] == 0x50.toByte() && // 'P'
                data[offset + 2] == 0x4E.toByte() && // 'N'
                data[offset + 3] == 0x47.toByte()    // 'G'
    }

    /**
     * Manually decodes a classic BITMAPINFOHEADER DIB (the pre-Vista ICO/CUR image
     * format): a 40-byte header, an optional BGRA palette for indexed depths, a
     * bottom-up XOR color layer, and a bottom-up 1bpp AND transparency mask.
     */
    private fun decodeDib(data: ByteArray, offset: Int, length: Int): Bitmap? {
        if (length < 40) return null
        val header = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = header.int
        if (headerSize < 40) return null
        val width = header.int
        val rawHeight = header.int
        // rawHeight covers the XOR layer AND the AND mask stacked together.
        val height = rawHeight / 2
        header.short // biPlanes, unused
        val bitCount = header.short.toInt() and 0xFFFF
        val compression = header.int
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) return null
        if (compression != 0) return null // BI_RGB only - compressed cursor DIBs aren't a real thing in practice

        var pos = offset + headerSize

        var palette: IntArray? = null
        if (bitCount <= 8) {
            val paletteCount = 1 shl bitCount
            val pal = IntArray(paletteCount)
            for (i in 0 until paletteCount) {
                if (pos + 4 > offset + length) break
                val b = data[pos].toInt() and 0xFF
                val g = data[pos + 1].toInt() and 0xFF
                val r = data[pos + 2].toInt() and 0xFF
                pal[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                pos += 4
            }
            palette = pal
        }

        val xorRowStride = ((width * bitCount + 31) / 32) * 4
        val xorDataStart = pos
        val andRowStride = ((width + 31) / 32) * 4
        val andDataStart = xorDataStart + xorRowStride * height
        val andDataAvailable = (offset + length) - andDataStart >= andRowStride * height

        val pixels = IntArray(width * height)
        var anyNonZeroAlpha = false

        for (destRow in 0 until height) {
            // Storage is bottom-up: the last stored row is the top of the image.
            val srcRow = height - 1 - destRow
            val rowStart = xorDataStart + srcRow * xorRowStride
            for (col in 0 until width) {
                val argb: Int = when (bitCount) {
                    32 -> {
                        val p = rowStart + col * 4
                        if (p + 4 > data.size) 0 else {
                            val b = data[p].toInt() and 0xFF
                            val g = data[p + 1].toInt() and 0xFF
                            val r = data[p + 2].toInt() and 0xFF
                            val a = data[p + 3].toInt() and 0xFF
                            if (a != 0) anyNonZeroAlpha = true
                            (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    24 -> {
                        val p = rowStart + col * 3
                        if (p + 3 > data.size) 0 else {
                            val b = data[p].toInt() and 0xFF
                            val g = data[p + 1].toInt() and 0xFF
                            val r = data[p + 2].toInt() and 0xFF
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    8 -> {
                        val p = rowStart + col
                        val idx = if (p < data.size) data[p].toInt() and 0xFF else 0
                        palette?.getOrElse(idx) { 0 } ?: 0
                    }
                    4 -> {
                        val byteIndex = rowStart + col / 2
                        if (byteIndex >= data.size) 0 else {
                            val byteVal = data[byteIndex].toInt() and 0xFF
                            val idx = if (col % 2 == 0) (byteVal shr 4) and 0xF else byteVal and 0xF
                            palette?.getOrElse(idx) { 0 } ?: 0
                        }
                    }
                    1 -> {
                        val byteIndex = rowStart + col / 8
                        if (byteIndex >= data.size) 0 else {
                            val byteVal = data[byteIndex].toInt() and 0xFF
                            val bit = 7 - (col % 8)
                            val idx = (byteVal shr bit) and 0x1
                            palette?.getOrElse(idx) { 0 } ?: 0
                        }
                    }
                    else -> 0
                }
                pixels[destRow * width + col] = argb
            }
        }

        // Composite in the AND mask for anything that doesn't already carry real
        // per-pixel alpha (every depth below 32bpp), and as a safety net for 32bpp
        // images whose alpha channel came back entirely zero (some older cursor
        // authoring tools write a dummy all-zero alpha channel and rely on the mask).
        if (andDataAvailable && (bitCount != 32 || !anyNonZeroAlpha)) {
            for (destRow in 0 until height) {
                val srcRow = height - 1 - destRow
                val andRowStart = andDataStart + srcRow * andRowStride
                for (col in 0 until width) {
                    val byteIndex = andRowStart + col / 8
                    if (byteIndex >= data.size) continue
                    val byteVal = data[byteIndex].toInt() and 0xFF
                    val bit = 7 - (col % 8)
                    val transparent = ((byteVal shr bit) and 0x1) == 1
                    val i = destRow * width + col
                    pixels[i] = if (transparent) {
                        pixels[i] and 0x00FFFFFF
                    } else {
                        pixels[i] or (0xFF shl 24)
                    }
                }
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
