package kr.co.donghyun.turtlelauncher.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.co.donghyun.turtlelauncher.data.instance.InstanceManager
import kr.co.donghyun.turtlelauncher.domain.model.InstalledMod
import kr.co.donghyun.turtlelauncher.domain.repository.InstanceDetailRepository
import kr.co.donghyun.turtlelauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.turtlelauncher.presentation.util.mods.ModImporter
import kr.co.donghyun.turtlelauncher.presentation.util.mods.ModpackExporter
import kr.co.donghyun.turtlelauncher.presentation.util.mods.ModpackImporter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstanceDetailRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstanceDetailRepository {

    override fun getInstalledMods(instanceId: String): List<InstalledMod> {
        val modsDir = File(gameDirForInstance(instanceId), "mods")
        if (!modsDir.isDirectory) return emptyList()
        return modsDir.listFiles()
            ?.filter { f ->
                f.isFile && (
                    f.name.endsWith(".jar", ignoreCase = true) ||
                    f.name.endsWith(".jar.disabled", ignoreCase = true)
                )
            }
            ?.map { f ->
                val enabled = f.name.endsWith(".jar", ignoreCase = true)
                val display = f.name.removeSuffix(".disabled").removeSuffix(".jar")
                InstalledMod(fileName = f.name, displayName = display, enabled = enabled, sizeBytes = f.length())
            }
            ?.sortedWith(compareByDescending<InstalledMod> { it.enabled }.thenBy { it.displayName.lowercase() })
            ?: emptyList()
    }

    override fun deleteMod(instanceId: String, fileName: String) {
        val modsDir = File(gameDirForInstance(instanceId), "mods")
        val target = File(modsDir, fileName)
        if (target.parentFile?.canonicalFile != modsDir.canonicalFile) {
            Log.w("TURTLE_LAUNCHER", "모드 삭제 거부(경로 이상): $fileName")
            return
        }
        val ok = runCatching { target.delete() }.getOrDefault(false)
        if (ok) Log.d("TURTLE_LAUNCHER", "🗑 모드 삭제: $fileName")
        else Log.w("TURTLE_LAUNCHER", "모드 삭제 실패: $fileName")
    }

    override fun detectLoaderLabel(instanceId: String): String? {
        val meta = runCatching {
            InstanceManager.loadMeta(InstanceManager.instanceDir(context, instanceId))
        }.getOrNull()
        return when (meta?.loaderType?.lowercase()) {
            "forge"    -> "Forge"
            "neoforge" -> "NeoForge"
            "fabric"   -> "Fabric"
            "quilt"    -> "Quilt"
            else       -> null
        }
    }

    override fun getRendererId(instanceId: String): String? =
        InstanceManager.loadRendererId(context, instanceId)

    override fun setRendererId(instanceId: String, rendererId: String?) {
        InstanceManager.updateRendererId(context, instanceId, rendererId)
    }

    override suspend fun deleteInstance(instanceId: String) {
        InstanceManager.deleteInstance(context, instanceId)
    }

    override suspend fun importMap(instanceId: String, zipUri: Uri): MapImporter.Result {
        val savesDir = File(gameDirForInstance(instanceId), "saves")
        return MapImporter.importZip(context = context, zipUri = zipUri, savesDir = savesDir)
    }

    override suspend fun importMods(instanceId: String, jarUris: List<Uri>): ModImporter.Result {
        val modsDir = File(gameDirForInstance(instanceId), "mods")
        return ModImporter.importJars(context = context, uris = jarUris, modsDir = modsDir)
    }

    override suspend fun importModpack(instanceId: String, zipUri: Uri): ModpackImporter.Result {
        val gameDir = gameDirForInstance(instanceId)
        val meta = runCatching {
            InstanceManager.loadMeta(InstanceManager.instanceDir(context, instanceId))
        }.getOrNull()
        return ModpackImporter.import(
            context = context,
            zipUri = zipUri,
            gameDir = gameDir,
            currentMcVersion = meta?.mcVersion,
            currentLoaderType = meta?.loaderType,
        )
    }

    override suspend fun exportModpack(instanceId: String, displayName: String, outputUri: Uri): ModpackExporter.Result {
        val gameDir = gameDirForInstance(instanceId)
        val modsDir = File(gameDir, "mods")
        val configDir = File(gameDir, "config").takeIf { it.isDirectory }
        val meta = runCatching {
            InstanceManager.loadMeta(InstanceManager.instanceDir(context, instanceId))
        }.getOrNull()
        return ModpackExporter.export(
            context = context,
            outputUri = outputUri,
            modsDir = modsDir,
            configDir = configDir,
            meta = meta,
            displayName = displayName,
        )
    }

    override fun hasExportableMods(instanceId: String): Boolean {
        val modsDir = File(gameDirForInstance(instanceId), "mods")
        return modsDir.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
            ?.isNotEmpty() == true
    }

    /** 모드/월드가 들어갈 게임 디렉터리. 1.12.2 이하(legacy)는 <instance>/.minecraft, 1.13+ 는 <instance> 자체. */
    private fun gameDirForInstance(instanceId: String): File {
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val mcVersion = runCatching { InstanceManager.loadMeta(instanceDir)?.mcVersion }.getOrNull() ?: ""
        return if (isLegacyVersion(mcVersion)) File(instanceDir, ".minecraft") else instanceDir
    }

    private fun isLegacyVersion(versionId: String): Boolean {
        val id = versionId.trim().lowercase()
        if (id.startsWith("b1.") || id.startsWith("a1.") || id.startsWith("a0.") ||
            id.startsWith("c0.") || id.startsWith("inf-") || id.startsWith("rd-")) {
            return true
        }
        val major = Regex("""^1\.(\d+)""").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return major <= 12
    }
}
