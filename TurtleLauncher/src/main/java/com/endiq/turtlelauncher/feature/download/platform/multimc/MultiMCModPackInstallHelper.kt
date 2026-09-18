package com.endiq.turtlelauncher.feature.download.platform.multimc

import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.item.ModLoaderWrapper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.models.MMCPackMeta
import net.endiq.launcher.Tools
import net.endiq.launcher.utils.ZipUtils
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * MultiMC/PrismLauncher instance-export (.zip, mmc-pack.json + instance.cfg) installer.
 * Real difference from CurseForgeModPackInstallHelper/ModrinthModPackInstallHelper: those
 * manifests only list mods to download afterward; a MultiMC export already has the actual
 * .minecraft-equivalent content sitting in the zip - normally under
 * `<InstanceName>/.minecraft/` (MultiMC's own "Export Instance" wraps everything in a
 * top-level folder named after the instance), occasionally bare `.minecraft/` for a
 * hand-built zip - so there's nothing to fetch, just extract it (same shape as CurseForge's
 * "overrides/" extraction below), then hand the version+loader found in mmc-pack.json to
 * TurtleLauncher's normal installer pipeline.
 */
class MultiMCModPackInstallHelper {
    companion object {
        private const val TAG = "MultiMCModPackInstallHelper"

        @Throws(Exception::class)
        fun installZip(packFile: File, targetPath: File): ModLoaderWrapper? {
            ZipFile(packFile).use { zip ->
                val basePath = findBasePath(zip)
                if (basePath == null) {
                    Logging.i(TAG, "No mmc-pack.json found at the zip root or one folder deep")
                    return null
                }

                val packMeta = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(zip.getInputStream(zip.getEntry("${basePath}mmc-pack.json"))),
                    MMCPackMeta::class.java
                )
                if (!verify(packMeta)) {
                    Logging.i(TAG, "mmc-pack.json verification failed")
                    return null
                }

                val contentPrefix = when {
                    entryExistsUnder(zip, "${basePath}.minecraft/") -> "${basePath}.minecraft/"
                    entryExistsUnder(zip, "${basePath}minecraft/") -> "${basePath}minecraft/"
                    else -> null
                }
                if (contentPrefix != null) {
                    ZipUtils.zipExtract(zip, contentPrefix, targetPath)
                } else {
                    Logging.i(TAG, "No .minecraft/ content folder under '$basePath' - importing loader/version only")
                }

                return createLoaderInfo(packMeta)
            }
        }

        /** Returns the entry-name prefix mmc-pack.json was found under ("" for root,
         *  "InstanceName/" for one folder deep - the normal case for a real MultiMC/Prism
         *  "Export Instance" zip), or null if this isn't a MultiMC-shaped export at all. */
        fun findBasePath(zip: ZipFile): String? {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name == "mmc-pack.json") return ""
                val firstSlash = name.indexOf('/')
                if (firstSlash > 0 && name == "${name.substring(0, firstSlash)}/mmc-pack.json") {
                    return name.substring(0, firstSlash + 1)
                }
            }
            return null
        }

        /** instance.cfg is a Java-Properties-style file (InstanceType=..., name=..., ...) -
         *  only the display name is used here. */
        fun readInstanceName(zip: ZipFile, basePath: String): String? {
            val entry = zip.getEntry("${basePath}instance.cfg") ?: return null
            return runCatching {
                val props = Properties()
                zip.getInputStream(entry).use { props.load(it) }
                props.getProperty("name")?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        fun verify(packMeta: MMCPackMeta?): Boolean {
            packMeta ?: return false
            return packMeta.components?.any { it.uid == "net.minecraft" && !it.version.isNullOrBlank() } == true
        }

        private fun entryExistsUnder(zip: ZipFile, prefix: String): Boolean {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (entries.nextElement().name.startsWith(prefix)) return true
            }
            return false
        }

        /** Null return means "no recognized loader component" - a vanilla MultiMC instance,
         *  or one using a loader this launcher doesn't support (e.g. LiteLoader). The
         *  .minecraft/ content is still extracted above either way, same tolerance
         *  CurseForgeModPackInstallHelper has for an unrecognized loader id. */
        private fun createLoaderInfo(packMeta: MMCPackMeta): ModLoaderWrapper? {
            val components = packMeta.components ?: return null
            val mcVersion = components.firstOrNull { it.uid == "net.minecraft" }?.version
                ?.takeIf { it.isNotBlank() } ?: return null

            val loaderComponent = components.firstOrNull {
                it.uid == "net.minecraftforge" || it.uid == "net.neoforged" ||
                        it.uid == "net.fabricmc.fabric-loader" || it.uid == "org.quiltmc.quilt-loader"
            } ?: return null

            val loaderVersion = loaderComponent.version?.takeIf { it.isNotBlank() } ?: return null
            val loader = when (loaderComponent.uid) {
                "net.minecraftforge" -> ModLoader.FORGE
                "net.neoforged" -> ModLoader.NEOFORGE
                "net.fabricmc.fabric-loader" -> ModLoader.FABRIC
                "org.quiltmc.quilt-loader" -> ModLoader.QUILT
                else -> return null
            }
            return ModLoaderWrapper(loader, loaderVersion, mcVersion)
        }
    }
}
