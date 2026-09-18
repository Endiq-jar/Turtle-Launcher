package com.endiq.turtlelauncher.feature.turtle.cursor

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AniDecoder {
    private const val JIFFY_MS = 1000.0 / 60.0
    private const val DEFAULT_JIFFY_RATE = 6 // ~100ms - only used if a file is missing anih entirely

    /** @return the decoded, playback-ordered frame sequence, or null if [data] isn't a valid .ani. */
    fun decode(data: ByteArray): List<CursorFrame>? {
        if (data.size < 12) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        if (readFourCC(buf) != "RIFF") return null
        val riffSize = buf.int
        if (readFourCC(buf) != "ACON") return null

        var cSteps = 0
        var jifRate = DEFAULT_JIFFY_RATE
        var rateArray: IntArray? = null
        var seqArray: IntArray? = null
        val iconDataList = ArrayList<ByteArray>()

        val end = minOf(data.size.toLong(), 8L + riffSize.toLong().let { if (it < 0) data.size.toLong() else it }).toInt()

        while (buf.position() + 8 <= end) {
            val chunkId = readFourCC(buf)
            val chunkSize = buf.int
            if (chunkSize < 0 || buf.position() + chunkSize > data.size) break // truncated/corrupt - stop rather than throw
            val chunkStart = buf.position()

            when (chunkId) {
                "anih" -> if (chunkSize >= 36) {
                    buf.int // cbSizeOf
                    buf.int // cFrames - not needed directly, storage order + seq/rate cover playback
                    cSteps = buf.int
                    buf.int; buf.int; buf.int; buf.int // cx, cy, cBitCount, cPlanes - real size comes from the decoded icons themselves
                    jifRate = buf.int
                    // flags (AF_ICON / AF_SEQ) intentionally unchecked - the rate/seq
                    // chunks being present or absent already tells us everything we
                    // need to know about how to play this file back.
                }
                "rate" -> rateArray = IntArray(chunkSize / 4) { buf.int }
                "seq " -> seqArray = IntArray(chunkSize / 4) { buf.int }
                "LIST" -> {
                    val listEnd = chunkStart + chunkSize
                    if (readFourCC(buf) == "fram") {
                        while (buf.position() + 8 <= listEnd) {
                            val subId = readFourCC(buf)
                            val subSize = buf.int
                            if (subSize < 0 || buf.position() + subSize > data.size) break
                            if (subId == "icon") {
                                val iconBytes = ByteArray(subSize)
                                buf.get(iconBytes)
                                iconDataList.add(iconBytes)
                            } else {
                                buf.position(buf.position() + subSize)
                            }
                            if (subSize % 2 != 0 && buf.position() < data.size) buf.position(buf.position() + 1)
                        }
                    }
                    // else: LIST INFO or other metadata - skip wholesale below
                }
                // Unknown/uninteresting chunk (e.g. "JUNK" padding) - skip wholesale below
            }

            // Unconditionally realign to the chunk's declared boundary regardless of how
            // much the branch above actually consumed - keeps parsing correct even if a
            // chunk carries more data than the fields above expect.
            buf.position(chunkStart + chunkSize)
            if (chunkSize % 2 != 0 && buf.position() < data.size) buf.position(buf.position() + 1)
        }

        if (iconDataList.isEmpty()) return null
        val decodedIcons = iconDataList.map { CurIcoDecoder.decode(it) }

        val steps = if (cSteps > 0) cSteps else decodedIcons.size
        val sequence = seqArray?.takeIf { it.isNotEmpty() } ?: IntArray(steps) { it % decodedIcons.size }

        val frames = ArrayList<CursorFrame>(sequence.size)
        for (i in sequence.indices) {
            val icon = decodedIcons.getOrNull(sequence[i]) ?: continue
            val delayJiffies = (rateArray?.getOrNull(i) ?: jifRate).coerceAtLeast(1)
            frames.add(
                CursorFrame(
                    icon.bitmap,
                    icon.hotspotX,
                    icon.hotspotY,
                    (delayJiffies * JIFFY_MS).toLong().coerceAtLeast(1L)
                )
            )
        }

        return frames.takeIf { it.isNotEmpty() }
    }

    private fun readFourCC(buf: ByteBuffer): String {
        val bytes = ByteArray(4)
        buf.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
