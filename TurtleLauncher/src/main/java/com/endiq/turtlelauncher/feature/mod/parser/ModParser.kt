package com.endiq.turtlelauncher.feature.mod.parser

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.moandjiezana.toml.Toml
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.utils.path.PathManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.jar.JarInputStream
import kotlin.jvm.Throws

class ModParser {
    companion object {
        /**
         * Collect the mod info of every mod in the current version.
         */
        @JvmStatic
        fun checkAllMods(minecraftVersion: Version, parserListener: ModParserListener) {
            File(minecraftVersion.getGameDir(), "mods").apply {
                if (exists() && isDirectory && (listFiles()?.isNotEmpty() == true)) {
                    ModParser().parseAllMods(this, parserListener)
                    return
                }
            }
            parserListener.onParseEnded(emptyList())
        }
    }

    /**
     * Parse every mod in the mods folder asynchronously.
     * @param modsFolder the mods folder
     * @param listener listener for the parsing process
     */
    fun parseAllMods(modsFolder: File, listener: ModParserListener) {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val cacheFileName = "${modsFolder.absolutePath.replace(File.separator, "-")}.cache"
        val infoCacheFile = File(PathManager.DIR_ADDONS_INFO_CACHE, cacheFileName)
        val modInfoQueue = ConcurrentLinkedQueue<ModInfo>()
        val newCacheMap = ConcurrentHashMap<String, ModInfoCache>()

        Task.runTask {
            if (modsFolder.isDirectory) {
                val files = modsFolder.listFiles()
                    ?.filter { it.extension.equals("jar", true) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@runTask

                val existingCache = loadCache(gson, infoCacheFile)

                runBlocking {
                    val optimalThreads = calculateOptimalThreads(files.size)
                    val semaphore = Semaphore(optimalThreads)

                    files.chunked(calculateChunkSize(files.size)).forEach { batch ->
                        val deferredList = batch.map { file ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    parseModFile(file, files.size, existingCache, newCacheMap, modInfoQueue, listener)
                                }
                            }
                        }
                        // Wait for every parse task to finish.
                        deferredList.awaitAll()
                    }
                }
            }
        }.onThrowable { e ->
            Logging.e("ModParser", "An exception occurred while parsing all mods!", e)
        }.finallyTask {
            persistCache(gson, infoCacheFile, newCacheMap.values.toList())
            listener.onParseEnded(modInfoQueue.toList())
        }.execute()
    }

    private fun parseModFile(
        modFile: File,
        totalCount: Int,
        existingCache: Map<String, ModInfoCache>,
        newCache: MutableMap<String, ModInfoCache>,
        modQueue: ConcurrentLinkedQueue<ModInfo>,
        listener: ModParserListener
    ) {
        // TurtleLauncher: was FileTools.calculateFileHash(modFile) - a full SHA-256 over the
        // jar's bytes, meaning every scan re-read every mod file in full just to find out most
        // of them were unchanged. fingerprintOf() is three stat() calls instead of a full read,
        // and is what actually makes "skip unchanged mods" cheap for a large modpack folder.
        val fingerprint = fingerprintOf(modFile)

        existingCache[fingerprint]?.let { cached ->
            val modInfo = cached.modInfo.apply { file = modFile }
            modQueue.add(modInfo)
            listener.onProgress(modInfo, totalCount)
            newCache[fingerprint] = cached
            return
        }

        parseModContents(modFile)?.let { modInfo ->
            modQueue.add(modInfo)
            listener.onProgress(modInfo, totalCount)
            newCache[fingerprint] = ModInfoCache(fingerprint, modInfo)
        }
    }

    /**
     * Cheap change-detection key for a mod jar: name + size + lastModified. Deliberately not a
     * content hash - the point is to decide whether to bother reading the file at all.
     */
    private fun fingerprintOf(file: File): String = "${file.name}:${file.length()}:${file.lastModified()}"

    private fun parseModContents(modFile: File): ModInfo? {
        return try {
            JarInputStream(FileInputStream(modFile)).use { jarStream ->
                locateModDescriptor(jarStream)?.let { entry ->
                    parseDescriptorContent(modFile, jarStream, entry.name)
                }
            }
        } catch (e: Exception) {
            Logging.e("ModParser", "Error parsing ${modFile.name}", e)
            null
        }
    }

    private fun locateModDescriptor(jarStream: JarInputStream): java.util.jar.JarEntry? {
        val targetFiles = setOf(
            "fabric.mod.json",
            "quilt.mod.json",
            "META-INF/neoforge.mods.toml",
            "META-INF/mods.toml",
            "mcmod.info"
        )

        return generateSequence { jarStream.nextJarEntry }
            .firstOrNull { it.name in targetFiles }
    }

    private fun parseDescriptorContent(modFile: File, jarStream: JarInputStream, fileName: String): ModInfo? {
        return when (fileName) {
            "fabric.mod.json" -> parseFabricMod(modFile, jarStream)
            "quilt.mod.json" -> parseQuiltMod(modFile, jarStream)
            "META-INF/neoforge.mods.toml", "META-INF/mods.toml" -> parseForgeMod(modFile, jarStream)
            "mcmod.info" -> parseLegacyForgeMod(modFile, jarStream)
            else -> null
        }
    }

    @Throws(Exception::class)
    private fun parseFabricMod(modFile: File, jarStream: JarInputStream): ModInfo {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val jsonObject = JsonParser.parseString(content).asJsonObject
        return ModInfo(
            jsonObject["id"].asString,
            jsonObject["version"].asString,
            jsonObject["name"].asString,
            jsonObject["description"].asString,
            jsonObject.get("authors").asJsonArray.let { authorsArray ->
                val authorsList = mutableListOf<String>()
                authorsArray.forEach { authorElement ->
                    val authorName: String = (
                            if (authorElement.isJsonObject) authorElement.asJsonObject?.get("name")?.asString
                            else authorElement.asString
                            ) ?: return@forEach
                    authorsList.add(authorName)
                }
                authorsList.toTypedArray()
            },
            parseFabricDependencies(jsonObject)
        ).apply { file = modFile }
    }

    /** Fabric "depends" is a flat object: { "modid": "version-predicate-or-array" } */
    private fun parseFabricDependencies(jsonObject: com.google.gson.JsonObject): Map<String, String> {
        return runCatching {
            jsonObject.getAsJsonObject("depends")?.entrySet()
                ?.associate { (key, value) ->
                    key to (if (value.isJsonPrimitive) value.asString else value.toString())
                }
                ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    @Throws(Exception::class)
    private fun parseQuiltMod(modFile: File, jarStream: JarInputStream): ModInfo {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val quiltLoader = JsonParser.parseString(content).asJsonObject["quilt_loader"].asJsonObject
        val metadata = quiltLoader["metadata"].asJsonObject
        return ModInfo(
            quiltLoader["id"].asString,
            quiltLoader["version"].asString,
            metadata["name"].asString,
            metadata["description"].asString,
            metadata["contributors"].asJsonObject.keySet().toTypedArray(),
            parseQuiltDependencies(quiltLoader)
        ).apply { file = modFile }
    }

    /** Quilt "depends" is an array; entries are either a plain modid string or { id, versions }. */
    private fun parseQuiltDependencies(quiltLoader: com.google.gson.JsonObject): Map<String, String> {
        return runCatching {
            val dependsArray = quiltLoader.getAsJsonArray("depends") ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            dependsArray.forEach { element ->
                when {
                    element.isJsonPrimitive -> map[element.asString] = "*"
                    element.isJsonObject -> {
                        val depObj = element.asJsonObject
                        val depId = depObj.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                        map[depId] = depObj.get("versions")?.takeIf { it.isJsonPrimitive }?.asString ?: "*"
                    }
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    @Throws(Exception::class)
    private fun parseForgeMod(modFile: File, jarStream: JarInputStream): ModInfo? {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val toml = Toml().read(content)
        val modEntry = toml.getTables("mods").firstOrNull() ?: return null
        val modId = modEntry.getString("modId") ?: return null

        return ModInfo(
            modId,
            modEntry.getString("version") ?: return null,
            modEntry.getString("displayName") ?: return null,
            modEntry.getString("description") ?: "",
            parseForgeAuthors(modEntry),
            parseForgeDependencies(toml, modId)
        ).apply { file = modFile }
    }

    /** mods.toml mandatory dependencies live under "[[dependencies.<ownModId>]]" array tables. */
    private fun parseForgeDependencies(toml: Toml, ownModId: String): Map<String, String> {
        return runCatching {
            val dependencyTables = toml.getTables("dependencies.$ownModId") ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            dependencyTables.forEach { depTable ->
                val depId = depTable.getString("modId") ?: return@forEach
                val mandatory = depTable.getBoolean("mandatory") ?: true
                if (mandatory) {
                    map[depId] = depTable.getString("versionRange") ?: "*"
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun parseForgeAuthors(modEntry: Toml): Array<String> {
        return when {
            modEntry.contains("authors") -> {
                modEntry.getString("authors")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.toTypedArray() ?: emptyArray()
            }
            else -> emptyArray()
        }
    }

    @Throws(Exception::class)
    private fun parseLegacyForgeMod(modFile: File, jarStream: JarInputStream): ModInfo? {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val jsonArray = JsonParser.parseString(content).asJsonArray
        val mainEntry = jsonArray[0].asJsonObject

        return ModInfo(
            mainEntry["modid"].asString ?: return null,
            mainEntry["version"].asString ?: return null,
            mainEntry["name"].asString ?: return null,
            mainEntry["description"]?.asString ?: "",
            parseLegacyForgeAuthors(mainEntry)
        ).apply { file = modFile }
    }

    private fun parseLegacyForgeAuthors(entry: com.google.gson.JsonObject): Array<String> {
        return when {
            entry.has("authorList") -> entry["authorList"].asJsonArray.toStringArray()
            entry.has("authors") -> entry["authors"].asJsonArray.toStringArray()
            else -> emptyArray()
        }
    }

    private fun JsonArray.toStringArray(): Array<String> {
        return this.map { it.asString }.toTypedArray()
    }

    private fun loadCache(gson: Gson, cacheFile: File): Map<String, ModInfoCache> {
        return cacheFile.takeIf { it.exists() }?.let {
            runCatching {
                gson.fromJson<List<ModInfoCache>>(
                    com.endiq.turtlelauncher.utils.CacheCompression.readCompressed(it),
                    object : TypeToken<List<ModInfoCache>>() {}.type
                )?.associateBy { it.cacheKey }
            }.getOrElse { e ->
                // Also catches a pre-update plain-JSON cache file that no longer matches the
                // Zstd frame this now expects - falls back to an empty (cold) cache either way,
                // which gets rebuilt and re-persisted in the new compressed format below.
                Logging.e("ModParser", "Cache load failed: ${it.absolutePath}", e)
                emptyMap()
            }
        } ?: emptyMap()
    }

    private fun persistCache(gson: Gson, cacheFile: File, data: List<ModInfoCache>) {
        Task.runTask {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                com.endiq.turtlelauncher.utils.CacheCompression.writeCompressed(cacheFile, gson.toJson(data))
            }.onFailure { e ->
                Logging.e("ModParser", "Cache save failed", e)
            }
        }.execute()
    }

    private fun calculateOptimalThreads(fileCount: Int): Int {
        val coreCount = Runtime.getRuntime().availableProcessors()
        return (fileCount.coerceAtMost(coreCount * 8))
            .coerceAtLeast(4)
    }

    private fun calculateChunkSize(totalFiles: Int): Int {
        return when {
            totalFiles < 50 -> 4
            totalFiles < 200 -> 32
            else -> 64
        }
    }

    private suspend fun <T> Semaphore.withPermit(action: suspend () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            release()
        }
    }
}
