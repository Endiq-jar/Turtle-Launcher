package com.endiq.turtlelauncher.feature.turtle.heatmap

import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.zip.ZipFile

/**
 * TurtleLauncher: "Performance Heatmap" (Section 11 of Endiq's mega-spec) - classifies
 * installed mods/resource packs/shader packs into 🟢/🟡/🔴 tiers.
 *
 * IMPORTANT HONESTY NOTE, see also [PerfEstimate]'s doc comment: this sandbox has no
 * device to run Minecraft on and actually profile FPS/RAM/VRAM/CPU against, and none of
 * TurtleLauncher's own code runs inside the game process to measure it live either. So
 * every number here is a *static* proxy computed from the file itself:
 *  - mods: how many Mixins it declares (more injected hooks -> plausibly more CPU
 *    overhead) and its uncompressed size (RAM proxy).
 *  - shader packs: how many composite/deferred/prepare render passes it defines, and
 *    whether it has a shadow pass - both real, standard shaderpack-authoring signals
 *    that correlate with GPU cost in practice.
 *  - resource packs: texture resolution vs. vanilla's 16px baseline (VRAM proxy).
 * These are genuinely useful "worth a second look" signals, not measured performance.
 * Best-effort throughout: anything that fails to parse is skipped (returns null), never
 * guessed.
 */
object HeatmapAnalyzer {
    private const val TAG = "HeatmapAnalyzer"
    private const val MAX_TEXTURES_SCANNED = 300
    private val PASS_FILE_REGEX = Regex("(composite|deferred|prepare)[0-9]*\\.(fsh|vsh|csh)")

    @JvmStatic
    fun analyze(file: File, category: String): PerfEstimate? {
        return runCatching {
            when (category.lowercase()) {
                "mods" -> analyzeMod(file)
                "resourcepacks" -> analyzePack(file, isShader = false)
                "shaderpacks" -> analyzePack(file, isShader = true)
                else -> null
            }
        }.onFailure { e ->
            Logging.i(TAG, "Heatmap analysis skipped for ${file.name}: ${e.message}")
        }.getOrNull()
    }

    // ── Mods ──────────────────────────────────────────────────────────────

    private fun analyzeMod(file: File): PerfEstimate? {
        if (!file.isFile) return null

        ZipFile(file).use { zip ->
            val entries = Collections.list(zip.entries())
            var uncompressedBytes = 0L
            for (e in entries) {
                if (e.size > 0) uncompressedBytes += e.size
            }

            var mixinDeclared = countFabricMixins(zip, entries)
            if (mixinDeclared == 0) mixinDeclared = countForgeMixins(zip, entries)

            var vramBytes = 0L
            var scanned = 0
            for (entry in entries) {
                if (scanned >= MAX_TEXTURES_SCANNED) break
                if (!entry.name.endsWith(".png", true)) continue
                if (!entry.name.contains("/textures/")) continue
                val dims = runCatching { zip.getInputStream(entry).use { readPngDimensions(it) } }.getOrNull()
                if (dims != null) {
                    vramBytes += dims.first.toLong() * dims.second.toLong() * 4L
                    scanned++
                }
            }

            val ramKb = uncompressedBytes / 1024
            val tier = scoreMod(mixinDeclared, ramKb)
            val reasoning = if (mixinDeclared > 0) {
                "$mixinDeclared declared mixin(s), ${formatKb(ramKb)} uncompressed"
            } else {
                "No mixin config found; ${formatKb(ramKb)} uncompressed (size-only estimate)"
            }
            return PerfEstimate(
                tier = tier,
                estimatedRamKb = ramKb,
                estimatedVramKb = vramBytes / 1024,
                cpuSignal = mixinDeclared,
                cpuSignalLabel = "mixins",
                reasoning = reasoning
            )
        }
    }

    /** Fabric declares its mixin config files in fabric.mod.json's "mixins" array. */
    private fun countFabricMixins(zip: ZipFile, entries: List<java.util.zip.ZipEntry>): Int {
        val fabricEntry = entries.find { it.name == "fabric.mod.json" } ?: return 0
        return runCatching {
            val json = zip.getInputStream(fabricEntry).bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(json).asJsonObject
            val mixinsArray = obj.getAsJsonArray("mixins")
            var total = 0
            if (mixinsArray != null) {
                for (mixinRef in mixinsArray) {
                    val configName = if (mixinRef.isJsonObject) {
                        mixinRef.asJsonObject.get("config")?.asString
                    } else {
                        mixinRef.asString
                    }
                    if (configName != null) total += countMixinsInConfig(zip, entries, configName)
                }
            }
            total
        }.getOrDefault(0)
    }

    /** Forge/NeoForge declare their mixin config files via a manifest header instead. */
    private fun countForgeMixins(zip: ZipFile, entries: List<java.util.zip.ZipEntry>): Int {
        val manifestEntry = entries.find { it.name == "META-INF/MANIFEST.MF" } ?: return 0
        return runCatching {
            val manifestText = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val line = manifestText.lineSequence().find { it.startsWith("MixinConfigs:") }
            var total = 0
            if (line != null) {
                line.substringAfter("MixinConfigs:").trim().split(",").forEach { cfgName ->
                    total += countMixinsInConfig(zip, entries, cfgName.trim())
                }
            }
            total
        }.getOrDefault(0)
    }

    private fun countMixinsInConfig(zip: ZipFile, entries: List<java.util.zip.ZipEntry>, configFileName: String): Int {
        val configEntry = entries.find { it.name.endsWith(configFileName) } ?: return 0
        return runCatching {
            val cfgJson = zip.getInputStream(configEntry).bufferedReader().use { it.readText() }
            val cfgObj = JsonParser.parseString(cfgJson).asJsonObject
            var count = 0
            for (section in listOf("mixins", "client", "server")) {
                cfgObj.getAsJsonArray(section)?.let { count += it.size() }
            }
            count
        }.getOrDefault(0)
    }

    private fun scoreMod(mixinCount: Int, ramKb: Long): PerfTier {
        val mixinTier = when {
            mixinCount >= 20 -> PerfTier.HEAVY
            mixinCount >= 6 -> PerfTier.MODERATE
            else -> PerfTier.LIGHT
        }
        val sizeTier = when {
            ramKb >= 10 * 1024 -> PerfTier.HEAVY
            ramKb >= 2 * 1024 -> PerfTier.MODERATE
            else -> PerfTier.LIGHT
        }
        return maxTier(mixinTier, sizeTier)
    }

    // ── Resource packs & shader packs ────────────────────────────────────

    private fun analyzePack(file: File, isShader: Boolean): PerfEstimate? {
        val reader = PackReader.open(file) ?: return null
        reader.use { r ->
            var vramBytes = 0L
            var texturesScanned = 0
            var totalTextures = 0
            var maxEdge = 0
            var passCount = 0
            var hasShadow = false
            var shadowMapRes = 0

            r.forEachEntry { name, openStream ->
                val base = name.substringAfterLast('/')
                if (base.endsWith(".png", true)) {
                    totalTextures++
                    if (texturesScanned < MAX_TEXTURES_SCANNED) {
                        val dims = runCatching { openStream().use { readPngDimensions(it) } }.getOrNull()
                        if (dims != null) {
                            vramBytes += dims.first.toLong() * dims.second.toLong() * 4L
                            if (dims.first > maxEdge) maxEdge = dims.first
                            if (dims.second > maxEdge) maxEdge = dims.second
                            texturesScanned++
                        }
                    }
                }
                if (isShader) {
                    if (PASS_FILE_REGEX.matches(base)) passCount++
                    if (base.startsWith("shadow.")) hasShadow = true
                    if (base == "shaders.properties") {
                        runCatching {
                            openStream().bufferedReader().forEachLine { line ->
                                val trimmed = line.trim()
                                if (trimmed.startsWith("shadowMapResolution")) {
                                    trimmed.substringAfter("=").trim().toIntOrNull()?.let { shadowMapRes = it }
                                }
                            }
                        }
                    }
                }
            }

            // If the scan was capped, extrapolate rather than silently under-reporting a
            // huge pack's VRAM footprint as if it were a small one.
            if (texturesScanned in 1 until totalTextures) {
                vramBytes = vramBytes * totalTextures / texturesScanned
            }

            return if (isShader) {
                val tier = scoreShader(passCount, hasShadow, shadowMapRes)
                val reasoning = buildString {
                    append("$passCount composite/deferred/prepare pass(es)")
                    if (hasShadow) {
                        append(", shadow pass")
                        if (shadowMapRes > 0) append(" (${shadowMapRes}px map)")
                    }
                }
                PerfEstimate(
                    tier = tier,
                    estimatedRamKb = 0,
                    estimatedVramKb = vramBytes / 1024,
                    cpuSignal = passCount,
                    cpuSignalLabel = "render passes",
                    reasoning = reasoning
                )
            } else {
                if (totalTextures == 0) return null
                val tier = scoreResourcePack(maxEdge)
                val scanNote = if (texturesScanned < totalTextures) ", scanned $texturesScanned/$totalTextures textures" else ""
                PerfEstimate(
                    tier = tier,
                    estimatedRamKb = 0,
                    estimatedVramKb = vramBytes / 1024,
                    cpuSignal = 0,
                    cpuSignalLabel = "n/a (asset-only)",
                    reasoning = "Largest texture edge ~${maxEdge}px (vanilla is 16px)$scanNote"
                )
            }
        }
    }

    private fun scoreShader(passCount: Int, hasShadow: Boolean, shadowMapRes: Int): PerfTier {
        return when {
            passCount >= 8 || shadowMapRes >= 4096 -> PerfTier.HEAVY
            passCount >= 3 || hasShadow -> PerfTier.MODERATE
            else -> PerfTier.LIGHT
        }
    }

    private fun scoreResourcePack(maxEdge: Int): PerfTier {
        return when {
            maxEdge > 64 -> PerfTier.HEAVY
            maxEdge > 16 -> PerfTier.MODERATE
            else -> PerfTier.LIGHT
        }
    }

    private fun maxTier(a: PerfTier, b: PerfTier): PerfTier = if (a.ordinal >= b.ordinal) a else b

    private fun formatKb(kb: Long): String =
        if (kb >= 1024) "~%.1f MB".format(kb / 1024.0) else "~${kb}KB"

    /**
     * PNG IHDR is fixed-layout: 8-byte signature, then a 4-byte chunk length, a 4-byte
     * "IHDR" tag, then width/height as 4-byte big-endian ints - 24 bytes total gets both,
     * no decoding of actual pixel data needed, so this is cheap even on a huge texture.
     */
    private fun readPngDimensions(input: InputStream): Pair<Int, Int>? {
        val header = ByteArray(24)
        var read = 0
        while (read < 24) {
            val n = input.read(header, read, 24 - read)
            if (n < 0) break
            read += n
        }
        if (read < 24) return null
        val sigOk = header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()
        if (!sigOk) return null
        val width = ((header[16].toInt() and 0xFF) shl 24) or ((header[17].toInt() and 0xFF) shl 16) or
                ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
        val height = ((header[20].toInt() and 0xFF) shl 24) or ((header[21].toInt() and 0xFF) shl 16) or
                ((header[22].toInt() and 0xFF) shl 8) or (header[23].toInt() and 0xFF)
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null
        return width to height
    }
}
