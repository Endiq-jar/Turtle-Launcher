package com.endiq.turtlelauncher.feature.mod

import com.google.gson.JsonParser
import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File
import java.util.zip.ZipFile

object ModConflictDetector {

    data class Conflict(
        /** Fully-qualified Minecraft (or library) class that two+ mods both target. */
        val targetClass: String,
        /** Display names (falls back to file name) of every mod that mixes into [targetClass]. */
        val modNames: List<String>
    )

    @JvmStatic
    fun detectConflicts(modsFolder: File): List<Conflict> {
        if (!modsFolder.isDirectory) return emptyList()
        val jarFiles = modsFolder.listFiles { f -> f.isFile && f.extension.equals("jar", true) }
            ?: return emptyList()

        // targetClass -> set of mod display names that mixin into it
        val targetsToMods = LinkedHashMap<String, LinkedHashSet<String>>()

        for (jar in jarFiles) {
            runCatching {
                val (modName, mixinTargets) = extractMixinTargets(jar)
                if (mixinTargets.isEmpty()) return@runCatching
                for (target in mixinTargets) {
                    targetsToMods.getOrPut(target) { LinkedHashSet() }.add(modName)
                }
            }.onFailure { e ->
                Logging.i("ModConflictDetector", "Skipped ${jar.name} (couldn't read mixin config)", e)
            }
        }

        return targetsToMods
            .filter { (_, mods) -> mods.size > 1 }
            .map { (target, mods) -> Conflict(target, mods.toList()) }
            .sortedByDescending { it.modNames.size }
    }

    /** Returns (mod display name, set of fully-qualified target classes this jar mixes into). */
    private fun extractMixinTargets(jar: File): Pair<String, Set<String>> {
        ZipFile(jar).use { zip ->
            // ── Fabric / Quilt ────────────────────────────────────────────────
            val fabricEntry = zip.getEntry("fabric.mod.json") ?: zip.getEntry("quilt.mod.json")
            if (fabricEntry != null) {
                return extractFabricMixinTargets(zip, fabricEntry, jar)
            }

            // ── Forge / NeoForge ─────────────────────────────────────────────
            // Forge mods declare mixins via @Mod and @MixinConfigs annotations on the
            // main mod class, or (more commonly for modern Forge) in mods.toml under
            // [[mixins]] table. We read the MANIFEST.MF MixinConfigs attribute as the
            // most reliable cross-version signal, then fall back to scanning mods.toml.
            val modName = extractForgeModName(zip, jar)
            val mixinConfigNames = mutableSetOf<String>()

            // Method 1: META-INF/MANIFEST.MF -> MixinConfigs attribute
            zip.getEntry("META-INF/MANIFEST.MF")?.let { mfEntry ->
                zip.getInputStream(mfEntry).bufferedReader().forEachLine { line ->
                    if (line.startsWith("MixinConfigs:")) {
                        line.removePrefix("MixinConfigs:").trim()
                            .split(",").forEach { mixinConfigNames.add(it.trim()) }
                    }
                }
            }

            // Method 2: mods.toml [[mixins]] table
            if (mixinConfigNames.isEmpty()) {
                zip.getEntry("META-INF/mods.toml")?.let { tomlEntry ->
                    val toml = zip.getInputStream(tomlEntry).bufferedReader().use { it.readText() }
                    // Minimal TOML parse: find config = "some.mixins.json" under [[mixins]]
                    Regex("config\\s*=\\s*\"([^\"]+\\.json)\"").findAll(toml).forEach {
                        mixinConfigNames.add(it.groupValues[1])
                    }
                }
            }

            if (mixinConfigNames.isEmpty()) return modName to emptySet()

            val targets = mutableSetOf<String>()
            for (configName in mixinConfigNames) {
                val configEntry = zip.getEntry(configName) ?: continue
                runCatching {
                    val configJson = zip.getInputStream(configEntry).bufferedReader().use { it.readText() }
                    val mixinConfig = JsonParser.parseString(configJson).asJsonObject
                    val basePackage = mixinConfig.get("package")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    listOf("mixins", "client", "server").forEach { key ->
                        mixinConfig.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { cls ->
                            if (cls.isJsonPrimitive) {
                                val classPath = (if (basePackage.isNotEmpty())
                                    "${basePackage.replace('.', '/')}/${cls.asString}" else cls.asString)
                                    .replace('.', '/') + ".class"
                                val classEntry = zip.getEntry(classPath) ?: return@forEach
                                runCatching {
                                    targets.addAll(MixinTargetReader.readMixinTargets(
                                        zip.getInputStream(classEntry).use { it.readBytes() }
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            return modName to targets
        }
    }

    private fun extractFabricMixinTargets(zip: ZipFile, descriptorEntry: java.util.zip.ZipEntry, jar: File): Pair<String, Set<String>> {
        val descriptorJson = zip.getInputStream(descriptorEntry).bufferedReader().use { it.readText() }
        val descriptor = JsonParser.parseString(descriptorJson).asJsonObject
        val modName = descriptor.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: descriptor.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: jar.nameWithoutExtension

        val mixinConfigNames = mutableSetOf<String>()
        descriptor.get("mixins")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            when {
                element.isJsonPrimitive -> mixinConfigNames.add(element.asString)
                element.isJsonObject -> element.asJsonObject.get("config")
                    ?.takeIf { it.isJsonPrimitive }?.asString?.let { mixinConfigNames.add(it) }
            }
        }
        descriptor.get("quilt_loader")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("mixin")?.let { mixinNode ->
                when {
                    mixinNode.isJsonPrimitive -> mixinConfigNames.add(mixinNode.asString)
                    mixinNode.isJsonArray -> mixinNode.asJsonArray.forEach {
                        if (it.isJsonPrimitive) mixinConfigNames.add(it.asString)
                    }
                }
            }

        if (mixinConfigNames.isEmpty()) return modName to emptySet()

        val targets = mutableSetOf<String>()
        for (mixinConfigName in mixinConfigNames) {
            val configEntry = zip.getEntry(mixinConfigName) ?: continue
            runCatching {
                val configJson = zip.getInputStream(configEntry).bufferedReader().use { it.readText() }
                val mixinConfig = JsonParser.parseString(configJson).asJsonObject
                val basePackage = mixinConfig.get("package")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                listOf("mixins", "client", "server").forEach { key ->
                    mixinConfig.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { className ->
                        if (className.isJsonPrimitive) {
                            val classPath = (if (basePackage.isNotEmpty())
                                "${basePackage.replace('.', '/')}/${className.asString}" else className.asString)
                                .replace('.', '/') + ".class"
                            val classEntry = zip.getEntry(classPath) ?: return@forEach
                            runCatching {
                                targets.addAll(MixinTargetReader.readMixinTargets(
                                    zip.getInputStream(classEntry).use { it.readBytes() }
                                ))
                            }
                        }
                    }
                }
            }
        }
        return modName to targets
    }

    private fun extractForgeModName(zip: ZipFile, jar: File): String {
        zip.getEntry("META-INF/mods.toml")?.let { entry ->
            val toml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            Regex("displayName\\s*=\\s*\"([^\"]+)\"").find(toml)?.groupValues?.get(1)
                ?.let { return it }
        }
        return jar.nameWithoutExtension
    }
}
