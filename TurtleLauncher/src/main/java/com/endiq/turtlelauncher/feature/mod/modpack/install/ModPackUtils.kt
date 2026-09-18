package com.endiq.turtlelauncher.feature.mod.modpack.install

import android.app.Activity
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.utils.LauncherProfiles
import com.endiq.turtlelauncher.feature.download.item.ModLoaderWrapper
import com.endiq.turtlelauncher.feature.download.platform.multimc.MultiMCModPackInstallHelper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.models.CurseForgeManifest
import com.endiq.turtlelauncher.feature.mod.models.MCBBSPackMeta
import com.endiq.turtlelauncher.feature.mod.models.MMCPackMeta
import com.endiq.turtlelauncher.utils.runtime.SelectRuntimeUtils
import net.endiq.launcher.JavaGUILauncherActivity
import net.endiq.launcher.Tools
import net.endiq.launcher.modloaders.modpacks.models.ModrinthIndex
import java.io.File
import java.util.zip.ZipFile

class ModPackUtils {
    companion object {
        @JvmStatic
        fun determineModpack(modpack: File): ModPackInfo {
            val zipName = modpack.name
            // lastIndexOf() is -1 for extensionless file names - substring(-1) would
            // crash. Fall back to the whole name, which matches no known suffix and
            // correctly yields UNKNOWN below.
            val suffix = zipName.substring(zipName.lastIndexOf('.').takeIf { it >= 0 } ?: 0)
            runCatching {
                ZipFile(modpack).use { modpackZipFile ->
                    if (suffix == ".zip") {
                        val mcbbsEntry = modpackZipFile.getEntry("mcbbs.packmeta")
                        if (mcbbsEntry != null) {
                            val mcbbsPackMeta = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(modpackZipFile.getInputStream(mcbbsEntry)),
                                MCBBSPackMeta::class.java
                            )
                            if (verifyMCBBSPackMeta(mcbbsPackMeta)) return ModPackInfo(mcbbsPackMeta.name, ModPackEnum.MCBBS)
                        }

                        val manifestEntry = modpackZipFile.getEntry("manifest.json")
                        if (manifestEntry != null) {
                            val manifest = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(modpackZipFile.getInputStream(manifestEntry)),
                                CurseForgeManifest::class.java
                            )
                            if (verifyCurseForgeManifest(manifest)) return ModPackInfo(manifest.name, ModPackEnum.CURSEFORGE)
                        }

                        // MultiMC/PrismLauncher "Export Instance" zips wrap everything in a
                        // top-level folder named after the instance (occasionally at the
                        // root for a hand-built zip) - unlike mcbbs.packmeta/manifest.json
                        // above, this can't be a fixed-path getEntry() lookup.
                        val mmcBasePath = MultiMCModPackInstallHelper.findBasePath(modpackZipFile)
                        if (mmcBasePath != null) {
                            val mmcPackMeta = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(modpackZipFile.getInputStream(modpackZipFile.getEntry("${mmcBasePath}mmc-pack.json"))),
                                MMCPackMeta::class.java
                            )
                            if (verifyMMCPack(mmcPackMeta)) {
                                val instanceName = MultiMCModPackInstallHelper.readInstanceName(modpackZipFile, mmcBasePath)
                                return ModPackInfo(instanceName, ModPackEnum.MULTIMC)
                            }
                        }

                        return ModPackInfo(null, ModPackEnum.GENERIC_ZIP)
                    } else if (suffix == ".mrpack") {
                        val entry = modpackZipFile.getEntry("modrinth.index.json")
                        if (entry != null) {
                            val modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                                Tools.read(modpackZipFile.getInputStream(entry)),
                                ModrinthIndex::class.java
                            )
                            if (verifyModrinthIndex(modrinthIndex)) return ModPackInfo(modrinthIndex.name, ModPackEnum.MODRINTH)
                        }
                    }
                }
            }.onFailure { e ->
                Logging.e("determineModpack", "There was a problem checking the ModPack", e)
            }

            return ModPackInfo(null, ModPackEnum.UNKNOWN)
        }

        @JvmStatic
        fun verifyModrinthIndex(modrinthIndex: ModrinthIndex?): Boolean { // detect Modrinth modpacks via modrinth.index.json
            if (modrinthIndex == null) return false
            if ("minecraft" != modrinthIndex.game) return false
            if (modrinthIndex.formatVersion != 1) return false
            return modrinthIndex.dependencies != null
        }

        fun verifyMCBBSPackMeta(mcbbsPackMeta: MCBBSPackMeta?): Boolean { // detect MCBBS modpacks via mcbbs.packmeta
            if (mcbbsPackMeta == null) return false
            if ("minecraftModpack" != mcbbsPackMeta.manifestType) return false
            if (mcbbsPackMeta.manifestVersion != 2) return false
            if (mcbbsPackMeta.addons == null || mcbbsPackMeta.addons.isEmpty()) return false
            if (mcbbsPackMeta.addons[0].id == null) return false
            return (mcbbsPackMeta.addons[0].version != null)
        }

        /** Detects a CurseForge modpack export by its manifest.json (checked *after* MCBBS's
         *  mcbbs.packmeta, since both formats use plain .zip - a file can only be one or the
         *  other, this is just which signature file to look for). */
        @JvmStatic
        fun verifyCurseForgeManifest(manifest: CurseForgeManifest?): Boolean {
            if (manifest == null) return false
            if ("minecraftModpack" != manifest.manifestType) return false
            if (manifest.minecraft?.version == null) return false
            return true
        }

        /** Detects a MultiMC/PrismLauncher instance export by its mmc-pack.json (real,
         *  external, documented format - see MMCPackMeta's doc comment). A "net.minecraft"
         *  component with a real version is the one thing every such export has, loader
         *  components are optional (a vanilla instance export has none). */
        @JvmStatic
        fun verifyMMCPack(packMeta: MMCPackMeta): Boolean {
            return MultiMCModPackInstallHelper.verify(packMeta)
        }

        @JvmStatic
        @Throws(Throwable::class)
        fun startModLoaderInstall(modLoader: ModLoaderWrapper, activity: Activity, modInstallFile: File, customName: String) {
            modLoader.getInstallationIntent(activity, modInstallFile, customName)?.let { installIntent ->
                SelectRuntimeUtils.selectRuntime(activity, activity.getString(R.string.version_install_new_modloader, modLoader.modLoader.loaderName)) { jreName ->
                    LauncherProfiles.generateLauncherProfiles()
                    installIntent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName)
                    activity.startActivity(installIntent)
                }
            }
        }
    }

    enum class ModPackEnum {
        UNKNOWN, MCBBS, MODRINTH, CURSEFORGE, MULTIMC, GENERIC_ZIP
    }
}
