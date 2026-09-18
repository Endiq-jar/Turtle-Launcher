package com.endiq.turtlelauncher.feature.emotes

import com.google.gson.JsonParser
import com.moandjiezana.toml.Toml
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.ModrinthDirectApi
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object Emotes {

    private const val TAG = "Emotes"

    /** Official Emotecraft community emote library - free .emote downloads. */
    const val EMOTE_SITE_URL = "https://emotes.kosmx.dev"

    /** Modrinth project slug for Emotecraft. */
    const val EMOTECRAFT_MODRINTH_SLUG = "emotecraft"

    const val PLAYER_ANIMATOR_MODRINTH_SLUG = "player-animation-library"

    /** Mod-loader mod IDs under which Player Animation Library has shipped over time. */
    private val PLAYER_ANIMATOR_IDS = listOf("player_animation_library", "player_animator")

    /** Loader/platform-provided "dependencies" that are never real, installable mods. */
    private val DEPENDENCY_IGNORED_IDS = setOf(
        "minecraft", "java", "fabricloader", "fabric", "forge", "neoforge",
        "quilt_loader", "quilted_fabric_api", "fabric_api", "fabric-api"
    )

    /**
     * The `emotes` folder Emotecraft reads for the currently selected version:
     * `<gameDir>/emotes`, or the shared game home when no version is selected.
     * Created if missing. Never throws - falls back to the shared game home.
     */
    @JvmStatic
    fun emotesDir(): File {
        val base = runCatching { VersionsManager.getCurrentVersion()?.getGameDir() }
            .onFailure { e -> Logging.w(TAG, "Could not resolve the current version's game dir", e) }
            .getOrNull()
            ?: File(PathManager.DIR_GAME_HOME)
        val dir = File(base, "emotes")
        runCatching { if (!dir.exists()) dir.mkdirs() }
            .onFailure { e -> Logging.w(TAG, "Could not create the emotes folder", e) }
        return dir
    }

    /** The `mods` folder of the currently selected version (or the shared game home). */
    @JvmStatic
    fun modsDir(): File {
        val base = runCatching { VersionsManager.getCurrentVersion()?.getGameDir() }
            .getOrNull()
            ?: File(PathManager.DIR_GAME_HOME)
        return File(base, "mods")
    }

    @JvmStatic
    fun isEmotecraftInstalled(): Boolean {
        return runCatching {
            modsDir().listFiles()?.any { file ->
                val name = file.name.lowercase()
                name.endsWith(".jar") && (name.contains("emotecraft") || name.startsWith("emotes"))
            } ?: false
        }.onFailure { e ->
            Logging.w(TAG, "Could not scan the mods folder for Emotecraft", e)
        }.getOrDefault(false)
    }

    /**
     * Resolves the Minecraft version string and [ModLoader] for the currently selected version.
     */
    @JvmStatic
    fun getCurrentVersionInfo(): Pair<String?, ModLoader?> {
        val version = VersionsManager.getCurrentVersion() ?: return null to null
        val versionInfo = version.getVersionInfo()
        val mcVersion = versionInfo?.minecraftVersion ?: version.getVersionName()
        val loader = versionInfo?.loaderInfo?.firstNotNullOfOrNull { ModLoaderUtils.getModLoader(it.name) }
        return mcVersion to loader
    }

    /**
     * Whether Player Animation Library (Emotecraft's dependency) looks installed for the
     * current version: any jar in the mods folder whose name contains "player_anim",
     * "playeranim" or "player-animation". Same defensive scan as [isEmotecraftInstalled].
     */
    @JvmStatic
    fun isPlayerAnimatorInstalled(): Boolean {
        return runCatching {
            modsDir().listFiles()?.any { file ->
                val name = file.name.lowercase()
                name.endsWith(".jar") && (
                    name.contains("player_anim") ||
                    name.contains("playeranim") ||
                    name.contains("player-animation")
                )
            } ?: false
        }.onFailure { e ->
            Logging.w(TAG, "Could not scan the mods folder for Player Animation Library", e)
        }.getOrDefault(false)
    }

    @JvmStatic
    fun autoInstallEmotecraft(): String {
        val (mcVersion, loader) = getCurrentVersionInfo()
        if (mcVersion.isNullOrBlank()) {
            throw IllegalStateException("Minecraft version not detected.")
        }
        if (loader == null) {
            throw IllegalStateException("No supported mod loader (Fabric/Forge/NeoForge/Quilt) found for this version.")
        }
        val targetModsDir = modsDir()
        targetModsDir.mkdirs()

        val outcome = ModrinthDirectApi.downloadBestMatch(
            EMOTECRAFT_MODRINTH_SLUG,
            mcVersion,
            loader,
            targetModsDir
        ) ?: throw IOException("No compatible Emotecraft build found for $mcVersion on ${loader.loaderName}.")

        val dependencyOutcomes = installEmotecraftDependencies(mcVersion, loader, targetModsDir)

        return if (dependencyOutcomes.isEmpty()) {
            outcome
        } else {
            buildString {
                append(outcome)
                dependencyOutcomes.forEach { (name, result) ->
                    append("\n").append(name).append(": ").append(result)
                }
            }
        }
    }

    private fun installEmotecraftDependencies(mcVersion: String, loader: ModLoader, targetModsDir: File): List<Pair<String, String>> {
        val outcomes = mutableListOf<Pair<String, String>>()

        val emotecraftJar = findEmotecraftJar(targetModsDir)
        val declaredIds = emotecraftJar?.let { readDeclaredDependencies(it) }
        val dependencyIds: List<String> = if (declaredIds != null) {
            declaredIds.filter { !isIgnoredDependency(it) }
        } else {
            Logging.i(TAG, "Could not read Emotecraft's dependency list from its jar - falling back to the known Player Animation Library IDs")
            PLAYER_ANIMATOR_IDS
        }
        if (dependencyIds.isEmpty()) return emptyList()

        // Player Animation Library has shipped under two historical mod IDs
        // (player_animation_library / player_animator). If both ever appear in one list
        // (the fallback path), they're alternatives for the same library - one copy is
        // enough, and only when BOTH fail should the failure be reported.
        var playerAnimatorHandled = isPlayerAnimatorInstalled()
        val palIdsInList = dependencyIds.filter { it in PLAYER_ANIMATOR_IDS }
        var palFailures = 0

        for (dependencyId in dependencyIds) {
            val displayName = displayNameFor(dependencyId)
            if (dependencyId in PLAYER_ANIMATOR_IDS && playerAnimatorHandled) continue

            val result = runCatching {
                ModrinthDirectApi.downloadBestMatch(dependencyId, mcVersion, loader, targetModsDir)
            }.onFailure { e ->
                Logging.e(TAG, "Failed to resolve Emotecraft dependency '$dependencyId'", e)
            }.getOrNull()

            when {
                result != null -> {
                    outcomes += displayName to result
                    if (dependencyId in PLAYER_ANIMATOR_IDS) playerAnimatorHandled = true
                }
                dependencyId in PLAYER_ANIMATOR_IDS -> {
                    palFailures++
                    if (palFailures >= palIdsInList.size) {
                        outcomes += displayName to "could not be auto-installed (no matching build on Modrinth)"
                    }
                }
                else -> outcomes += displayName to "could not be auto-installed (no matching build on Modrinth)"
            }
        }

        return outcomes
    }

    /** Locates the Emotecraft jar inside [modsDir] (newest one, if several exist). */
    private fun findEmotecraftJar(modsDir: File): File? {
        return runCatching {
            modsDir.listFiles()?.filter { file ->
                val name = file.name.lowercase()
                file.isFile && name.endsWith(".jar") &&
                    (name.contains("emotecraft") || name.startsWith("emotes"))
            }?.maxByOrNull { it.lastModified() }
        }.getOrNull()
    }

    /**
     * Reads the mandatory dependency IDs declared by the mod jar's loader descriptor
     * (fabric.mod.json / quilt.mod.json / mods.toml). Returns null when the descriptor
     * can't be found or parsed, so callers can fall back to known IDs.
     */
    private fun readDeclaredDependencies(jarFile: File): List<String>? {
        return runCatching {
            ZipFile(jarFile).use { zip ->
                val fabricEntry = zip.getEntry("fabric.mod.json")
                if (fabricEntry != null) {
                    return@use parseFabricDepends(zip.getInputStream(fabricEntry).reader().readText())
                }
                val quiltEntry = zip.getEntry("quilt.mod.json")
                if (quiltEntry != null) {
                    return@use parseQuiltDepends(zip.getInputStream(quiltEntry).reader().readText())
                }
                val tomlEntry = zip.getEntry("META-INF/neoforge.mods.toml") ?: zip.getEntry("META-INF/mods.toml")
                if (tomlEntry != null) {
                    return@use parseTomlDepends(zip.getInputStream(tomlEntry).reader().readText())
                }
                null
            }
        }.onFailure { e ->
            Logging.w(TAG, "Failed to read dependencies from ${jarFile.name}", e)
        }.getOrNull()
    }

    /** Fabric's "depends" object: mod ID -> version requirement. */
    private fun parseFabricDepends(json: String): List<String> {
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            root.getAsJsonObject("depends")?.keySet()?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Quilt's quilt_loader.depends array entries are plain IDs or {id: ...} objects. */
    private fun parseQuiltDepends(json: String): List<String> {
        return runCatching {
            val loaderObj = JsonParser.parseString(json).asJsonObject.getAsJsonObject("quilt_loader")
            val depends = loaderObj?.getAsJsonArray("depends") ?: return@runCatching emptyList()
            depends.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> element.asString
                    element.isJsonObject -> element.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Forge/NeoForge mods.toml: mandatory entries under [[dependencies.<modId>]]. */
    private fun parseTomlDepends(tomlText: String): List<String> {
        return runCatching {
            val toml = Toml().read(tomlText)
            val modId = toml.getTables("mods").firstOrNull()?.getString("modId") ?: return@runCatching emptyList()
            val tables = toml.getTables("dependencies.$modId") ?: return@runCatching emptyList()
            tables.mapNotNull { table ->
                val dependencyId = table.getString("modId") ?: return@mapNotNull null
                val type = table.getString("type")
                // NeoForge uses type="required"; classic Forge uses mandatory=true.
                val required = if (type != null) type.equals("required", true)
                               else table.getBoolean("mandatory") ?: true
                if (required) dependencyId else null
            }
        }.getOrDefault(emptyList())
    }

    /** Loader/platform-provided IDs that must never be treated as downloadable mods. */
    private fun isIgnoredDependency(dependencyId: String): Boolean {
        val id = dependencyId.lowercase()
        if (id in DEPENDENCY_IGNORED_IDS) return true
        // Every "fabric-*"/"fabric_*" ID is either the loader itself or one of Fabric API's
        // bundled sub-modules (fabric-command-api-v2, fabric-rendering-v1, ...): they ship
        // inside the fabric-api/fabricloader jars and are never standalone Modrinth downloads.
        // Same reasoning for quilt_*/quilt-* (Quilt loader + QSL). Real third-party mod IDs
        // practically never take these prefixes (e.g. "fabrication" has no separator).
        if (id.startsWith("fabric-") || id.startsWith("fabric_")) return true
        if (id.startsWith("quilt-") || id.startsWith("quilt_")) return true
        return false
    }

    /** Friendly name for a dependency in result messages. */
    private fun displayNameFor(dependencyId: String): String {
        return if (dependencyId in PLAYER_ANIMATOR_IDS) "Player Animation Library" else dependencyId
    }
}
