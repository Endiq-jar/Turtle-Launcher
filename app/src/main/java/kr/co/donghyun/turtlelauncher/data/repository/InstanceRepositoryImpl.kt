package kr.co.donghyun.turtlelauncher.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.co.donghyun.turtlelauncher.data.instance.InstanceManager
import kr.co.donghyun.turtlelauncher.data.instance.InstanceMeta
import kr.co.donghyun.turtlelauncher.data.instance.InstanceType
import kr.co.donghyun.turtlelauncher.data.mapper.toData
import kr.co.donghyun.turtlelauncher.data.mapper.toDomain
import kr.co.donghyun.turtlelauncher.domain.model.DownloadProgress
import kr.co.donghyun.turtlelauncher.domain.model.Instance
import kr.co.donghyun.turtlelauncher.domain.model.LaunchParams
import kr.co.donghyun.turtlelauncher.domain.model.McVersion
import kr.co.donghyun.turtlelauncher.domain.model.MinecraftSupport
import kr.co.donghyun.turtlelauncher.domain.repository.InstanceRepository
import kr.co.donghyun.turtlelauncher.presentation.util.fabric.FabricInstaller
import kr.co.donghyun.turtlelauncher.presentation.util.forge.ForgeInstaller
import kr.co.donghyun.turtlelauncher.presentation.util.minecraft.MinecraftDownloader
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InstanceRepository 구현체. 기존 MainActivity 에 있던 다운로드/설치 로직(바닐라/Fabric/
 * Forge/NeoForge, native·lwjgl 준비)을 그대로 옮겨왔다 — 로직 자체를 새로 짜지 않고
 * 기존 InstanceManager/MinecraftDownloader/FabricInstaller/ForgeInstaller 를 그대로
 * 호출하는 어댑터 역할만 한다(회귀버그 위험을 최소화하기 위한 선택).
 */
@Singleton
class InstanceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstanceRepository {

    override suspend fun getInstances(): List<Instance> =
        InstanceManager.listInstances(context)
            .filter { MinecraftSupport.isSupported(it.mcVersion) }
            .sortedByDescending { InstanceManager.instanceDir(context, it.id).lastModified() }
            .map { it.toDomain() }

    override suspend fun installVanilla(
        version: McVersion,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams {
        require(MinecraftSupport.isSupported(version.id)) {
            "Minecraft ${version.id} is not supported; only ${MinecraftSupport.SUPPORTED_VERSION} can be installed"
        }
        val dataVersion = version.toData()
        val instanceId = InstanceManager.vanillaId(dataVersion.id)
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val internalBaseDir = context.filesDir
        val nativesDir = File(internalBaseDir, "natives")

        val preparer = MinecraftDownloader(
            instanceDir = instanceDir,
            versionEntry = dataVersion,
            onProgress = { onProgress(it.toDomain()) }
        )
        val result = preparer.prepare()
        val assetIndexId = result.assetIndexId
        val realMainClass = result.mainClass
        val legacyGameArgs = result.minecraftArguments
            ?.split(" ")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        copyNativesFromApkLibDir(nativesDir)
        copyLwjglJarFromAssets(internalBaseDir)
        prePopulateLwjglExtractDir(internalBaseDir, nativesDir, dataVersion.id)

        InstanceManager.saveMeta(
            context,
            InstanceMeta(
                id = instanceId,
                name = dataVersion.id,
                type = InstanceType.VANILLA,
                mcVersion = dataVersion.id,
                mainClass = realMainClass,
                assetIndexId = assetIndexId,
                iconEmoji = "🌿",
                gameArgs = legacyGameArgs
            )
        )

        return LaunchParams(
            versionId = dataVersion.id,
            assetIndexId = assetIndexId,
            mainClass = realMainClass,
            instanceDirPath = instanceDir.absolutePath,
        )
    }

    override suspend fun installFabric(
        version: McVersion,
        loaderVersion: String,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams {
        require(MinecraftSupport.isSupported(version.id)) {
            "Minecraft ${version.id} is not supported; only ${MinecraftSupport.SUPPORTED_VERSION} can be installed"
        }
        val dataVersion = version.toData()
        val mcVersion = dataVersion.id
        val instanceId = InstanceManager.fabricId(mcVersion, loaderVersion)
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val internalBaseDir = context.filesDir
        val nativesDir = File(internalBaseDir, "natives")

        val mcPreparer = MinecraftDownloader(
            instanceDir = instanceDir,
            versionEntry = dataVersion,
            onProgress = { onProgress(it.toDomain()) }
        )
        val manifest = mcPreparer.prepare()

        val fabricResult = FabricInstaller(instanceDir) { msg, cur, tot ->
            onProgress(DownloadProgress(
                phase = kr.co.donghyun.turtlelauncher.domain.model.DownloadPhase.DOWNLOADING_LIBRARIES,
                current = cur, total = tot, fileName = msg
            ))
        }.install(mcVersion, loaderVersion)

        if (!fabricResult.success) {
            throw Exception("Fabric 설치 실패: ${fabricResult.error}")
        }

        copyNativesFromApkLibDir(nativesDir)
        copyLwjglJarFromAssets(internalBaseDir)
        prePopulateLwjglExtractDir(internalBaseDir, nativesDir, mcVersion)
        File(instanceDir, "mods").mkdirs()

        InstanceManager.saveMeta(
            context,
            InstanceMeta(
                id = instanceId,
                name = "$mcVersion · Fabric $loaderVersion",
                type = InstanceType.FABRIC,
                mcVersion = mcVersion,
                loaderType = "fabric",
                loaderVersion = loaderVersion,
                mainClass = fabricResult.mainClass,
                extraJars = fabricResult.extraJars,
                assetIndexId = manifest.assetIndexId,
                iconEmoji = "🧵",
                gameJvmArgs = fabricResult.gameJvmArgs,
                gameArgs = fabricResult.gameArgs
            )
        )

        return LaunchParams(
            versionId = mcVersion,
            assetIndexId = manifest.assetIndexId,
            mainClass = fabricResult.mainClass,
            extraJars = fabricResult.extraJars,
            instanceDirPath = instanceDir.absolutePath,
        )
    }

    override suspend fun installForge(
        version: McVersion,
        loaderVersion: String,
        isNeoForge: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams {
        require(MinecraftSupport.isSupported(version.id)) {
            "Minecraft ${version.id} is not supported; only ${MinecraftSupport.SUPPORTED_VERSION} can be installed"
        }
        val dataVersion = version.toData()
        val mcVersion = dataVersion.id
        val loaderType = if (isNeoForge) "neoforge" else "forge"
        val instanceId = "${loaderType}_${mcVersion.replace('.', '_')}_${loaderVersion.replace('.', '_')}"
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val internalBaseDir = context.filesDir
        val nativesDir = File(internalBaseDir, "natives")

        val mcPreparer = MinecraftDownloader(
            instanceDir = instanceDir,
            versionEntry = dataVersion,
            onProgress = { onProgress(it.toDomain()) }
        )
        val manifest = mcPreparer.prepare()

        val forgeResult = ForgeInstaller(instanceDir) { msg, cur, tot ->
            onProgress(DownloadProgress(
                phase = kr.co.donghyun.turtlelauncher.domain.model.DownloadPhase.DOWNLOADING_LIBRARIES,
                current = cur, total = tot, fileName = msg
            ))
        }.install(context, mcVersion, loaderVersion, isNeoForge = isNeoForge)

        if (!forgeResult.success) {
            throw Exception("Forge 설치 실패: ${forgeResult.error}")
        }

        copyNativesFromApkLibDir(nativesDir)
        copyLwjglJarFromAssets(internalBaseDir)
        prePopulateLwjglExtractDir(internalBaseDir, nativesDir, mcVersion)
        File(instanceDir, "mods").mkdirs()

        InstanceManager.saveMeta(
            context,
            InstanceMeta(
                id = instanceId,
                name = "$mcVersion · ${if (isNeoForge) "NeoForge" else "Forge"} $loaderVersion",
                type = InstanceType.MODPACK,
                mcVersion = mcVersion,
                loaderType = loaderType,
                loaderVersion = loaderVersion,
                mainClass = forgeResult.mainClass,
                extraJars = forgeResult.extraJars,
                assetIndexId = manifest.assetIndexId,
                iconEmoji = if (isNeoForge) "🟢" else "🔥",
                gameJvmArgs = forgeResult.gameJvmArgs,
                gameArgs = forgeResult.gameArgs
            )
        )

        return LaunchParams(
            versionId = mcVersion,
            assetIndexId = manifest.assetIndexId,
            mainClass = forgeResult.mainClass,
            extraJars = forgeResult.extraJars,
            instanceDirPath = instanceDir.absolutePath,
        )
    }

    override suspend fun prepareLaunch(instance: Instance): LaunchParams {
        require(MinecraftSupport.isSupported(instance.mcVersion)) {
            "Minecraft ${instance.mcVersion} is not supported; only ${MinecraftSupport.SUPPORTED_VERSION} can be launched"
        }
        val instanceDir = InstanceManager.instanceDir(context, instance.id)
        val internalBase = context.filesDir
        val nativesDir = File(internalBase, "natives")

        copyNativesFromApkLibDir(nativesDir)
        copyLwjglJarFromAssets(internalBase)
        prePopulateLwjglExtractDir(internalBase, nativesDir, instance.mcVersion)

        return LaunchParams(
            versionId = instance.mcVersion,
            assetIndexId = instance.assetIndexId,
            mainClass = instance.mainClass,
            extraJars = instance.extraJars,
            instanceDirPath = instanceDir.absolutePath,
        )
    }

    // ── 아래는 MainActivity 에 있던 파일 준비 로직을 그대로 이관 ──────────────

    private fun prePopulateLwjglExtractDir(baseDir: File, nativesDir: File, versionId: String) {
        listOf("3.2.1", "3.2.2", "3.2.1-build-12", "3.2.2-build-12", "3.3.3", "3.3.3-snapshot").forEach { v ->
            val lwjglDir = File(context.getExternalFilesDir(null), "mc_$versionId/.lwjgl/$v")
            if (lwjglDir.exists()) lwjglDir.deleteRecursively()
            lwjglDir.mkdirs()
            nativesDir.listFiles()?.forEach { soFile ->
                soFile.copyTo(File(lwjglDir, soFile.name), overwrite = true)
                File(lwjglDir, soFile.name).setExecutable(true, false)
            }
        }
    }

    private fun copyNativesFromApkLibDir(nativesDir: File) {
        if (nativesDir.exists()) nativesDir.deleteRecursively()
        nativesDir.mkdirs()
        val apkLibDir = File(context.applicationInfo.nativeLibraryDir)
        apkLibDir.listFiles()?.forEach { soFile ->
            soFile.copyTo(File(nativesDir, soFile.name), overwrite = true)
            File(nativesDir, soFile.name).setExecutable(true, false)
        }
        val apkPath = context.applicationInfo.sourceDir
        ZipFile(apkPath).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val fileName = entry.name.substringAfterLast("/")
                    val dest = File(nativesDir, fileName)
                    if (!dest.exists()) {
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        dest.setExecutable(true, false)
                        dest.setReadable(true, false)
                    }
                }
        }
    }

    private fun copyLwjglJarFromAssets(baseDir: File) {
        val dest = File(baseDir, "lwjgl3/lwjgl-glfw-classes.jar")
        dest.parentFile?.mkdirs()
        context.assets.open("lwjgl3/lwjgl-glfw-classes.jar").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }
}
