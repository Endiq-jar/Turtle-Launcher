package com.endiq.turtlelauncher.feature.emotes

import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.ModrinthDirectApi
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File

/**
 * TurtleLauncher Emotes: shared constants and file helpers for the emote feature.
 *
 * Emotes in Minecraft Java are provided by the Emotecraft mod (KosmX, GPL-3.0,
 * Fabric/Forge/NeoForge/Quilt): it reads `.emote` files from the `emotes` folder inside
 * the game directory and plays them through its in-game emote wheel (default keybind B).
 * The launcher side of the feature therefore does three things:
 *
 *  1. Downloads emotes from the official community library (https://emotes.kosmx.dev) -
 *     either embedded in the Settings -> Emotes screen (EmotesFragment's WebView saves
 *     downloads straight into the emotes folder) or opened in the browser from the
 *     in-game menu.
 *  2. Points the mod at the right folder: [emotesDir] is `<gameDir>/emotes` of the
 *     currently selected version (falling back to the shared game home), created on
 *     demand so first-launch downloads have somewhere to land.
 *  3. Triggers the wheel in game: the game menu's "Play emote" button sends
 *     AllSettings.emoteWheelKeycode (default GLFW_KEY_B) through CallbackBridge, which
 *     is exactly the same key event a physical B press produces.
 *
 * Everything here is defensive on purpose: file listing/parsing must never throw into a
 * click handler or the game process.
 */
object Emotes {

    private const val TAG = "Emotes"

    /** Official Emotecraft community emote library - free .emote downloads. */
    const val EMOTE_SITE_URL = "https://emotes.kosmx.dev"

    /** Modrinth project slug for Emotecraft. */
    const val EMOTECRAFT_MODRINTH_SLUG = "emotecraft"

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

    /**
     * Whether Emotecraft looks installed for the current version: any jar in the mods
     * folder whose name contains "emotecraft" or starts with "emotes" (Emotecraft's own
     * artifact names across loaders are emotecraft-*, Emotecraft-*, emotes-*). A mod
     * folder that can't be listed reads as "not installed" rather than throwing.
     */
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
     * Attempts to automatically download and install Emotecraft for the current version
     * from Modrinth. Runs synchronously (network call included) - must be called from
     * a background thread.
     *
     * @return Result message or null on success, or throws Exception on failure.
     */
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

        return outcome
    }
}
