package kr.co.donghyun.turtlelauncher.data.repository

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kr.co.donghyun.turtlelauncher.BuildConfig
import kr.co.donghyun.turtlelauncher.data.auth.LocalAccountManager
import kr.co.donghyun.turtlelauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.turtlelauncher.data.auth.ThirdPartyAuthManager
import kr.co.donghyun.turtlelauncher.data.instance.InstanceManager
import kr.co.donghyun.turtlelauncher.data.instance.InstanceMeta
import kr.co.donghyun.turtlelauncher.data.instance.InstanceType
import kr.co.donghyun.turtlelauncher.data.mods.ContentItem
import kr.co.donghyun.turtlelauncher.data.mods.ContentSource
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeFile
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeListResponse
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeLogo
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeMod
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeResponse
import kr.co.donghyun.turtlelauncher.data.mods.ModrinthVersion
import kr.co.donghyun.turtlelauncher.data.mods.ModrinthVersionFile
import kr.co.donghyun.turtlelauncher.data.mojang.DownloadPhase
import kr.co.donghyun.turtlelauncher.data.mojang.DownloadProgress
import kr.co.donghyun.turtlelauncher.data.mojang.VersionEntry
import kr.co.donghyun.turtlelauncher.presentation.ContentPackDetailActivity
import kr.co.donghyun.turtlelauncher.presentation.ModLoader
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.turtlelauncher.presentation.util.fabric.FabricInstaller
import kr.co.donghyun.turtlelauncher.presentation.util.fabric.FabricMetaAPI
import kr.co.donghyun.turtlelauncher.presentation.util.quilt.QuiltInstaller
import kr.co.donghyun.turtlelauncher.presentation.util.forge.ForgeInstaller
import kr.co.donghyun.turtlelauncher.presentation.util.forge.ForgeMetaAPI
import kr.co.donghyun.turtlelauncher.presentation.util.forge.NeoForgeMetaAPI
import kr.co.donghyun.turtlelauncher.presentation.util.minecraft.MinecraftDownloader
import kr.co.donghyun.turtlelauncher.presentation.util.minecraft.VersionRepository
import kr.co.donghyun.turtlelauncher.presentation.util.mods.CurseForgeAPI
import kr.co.donghyun.turtlelauncher.presentation.util.mods.ModPackInstaller
import kr.co.donghyun.turtlelauncher.presentation.util.mods.ModrinthAPI
import kr.co.donghyun.turtlelauncher.presentation.util.mods.MrpackInstaller
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 콘텐츠(모드팩/모드/텍스처팩/쉐이더팩) 설치 로직 리포지토리.
 *
 * ⚠️ 이 클래스는 ContentPackBrowserActivity 에 있던 설치 관련 코드(installDirect/
 * installModrinthModpack/installToExistingInstance/installToNewInstance/launchMod 등
 * 2000줄 가량)를 거의 그대로 이관한 것이다 — 로직을 새로 짜지 않고 어댑터로 옮기는
 * 방식(다른 리포지토리들과 동일한 원칙)으로 회귀버그 위험을 최소화했다.
 *
 * ⚠️ 인터페이스를 따로 두지 않고 이 구현체를 바로 Hilt 로 주입한다 — 테스트 대체
 * 구현체가 필요 없는 단일 구현 클래스라 인터페이스 보일러플레이트의 실익이 낮다고 판단
 * (Google 가이드도 이런 경우 인터페이스를 필수로 요구하지 않는다).
 */
@Singleton
class ContentInstallRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _installingModId = MutableStateFlow<String?>(null)
    val installingModId: StateFlow<String?> = _installingModId.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** 설치 불가(인스턴스-콘텐츠 버전 불일치 등) 시 띄우는 에러 다이얼로그 메시지. null 이면 숨김. */
    private val _errorDialogMessage = MutableStateFlow<String?>(null)
    val errorDialogMessage: StateFlow<String?> = _errorDialogMessage.asStateFlow()

    fun clearErrorDialog() { _errorDialogMessage.value = null }

    // CurseForge Podium project ID (modrinth: "podium", CF slug: "podium-sodium")
    private val PODIUM_MOD_ID = 1241894   // ← CurseForge "Podium (Pojav x Sodium)"

    // Fabric 모드는 거의 다 Fabric API 에 묶임. CurseForge 의존성에 등록 안 된 경우가
    // 많아서 일괄로 보장한다. NeoForge / Forge 는 API 가 로더에 내장이라 별도 처리 불필요.
    private val FABRIC_API_MOD_ID = 306612

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private val modrinthApi = ModrinthAPI()


    /**
     * CurseForge 의 월드(맵) zip 을 받아 인스턴스의 saves/ 에 추출.
     * zip 레이아웃 두 가지를 모두 허용:
     *  A) WorldName/level.dat       ← 가장 흔함
     *  B) level.dat                  ← zip root 가 곧 월드
     * 같은 이름의 월드가 이미 있으면 " (1)", " (2)" 붙여서 충돌 회피.
     */
    private suspend fun installWorld(
        file: CurseForgeFile,
        instanceDir: File,
        mcVersion: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val savesDir = if (isLegacyVersion(mcVersion))
            instanceDir.resolve(".minecraft/saves")
        else
            instanceDir.resolve("saves")
        savesDir.mkdirs()

        val tmpZip = File.createTempFile("world-", ".zip", context.cacheDir)
        try {
            // 1) 다운로드
            val url = resolveDownloadUrl(file)
            downloadFile(url, tmpZip, file.fileName)

            // 2) zip 안에서 level.dat 위치 추적
            val worldRoot = findWorldRootInZip(tmpZip) ?: run {
                Log.e("TURTLE_LAUNCHER", "🗺️ zip 안에 level.dat 없음 — 맵 아님? ${file.fileName}")
                return@withContext false
            }
            Log.d("TURTLE_LAUNCHER", "🗺️ worldRoot in zip = '${worldRoot.ifEmpty { "(root)" }}'")

            // 3) 폴더명 결정
            val baseName = when {
                worldRoot.isEmpty() -> file.fileName
                    .substringBeforeLast(".zip")
                    .substringBeforeLast(".")
                    .replace(Regex("[^A-Za-z0-9가-힣 _.\\-]"), "_")
                    .trim()
                    .ifEmpty { "ImportedWorld" }
                else -> worldRoot.trimEnd('/').substringAfterLast('/')
            }

            // 4) 충돌 회피
            var target = savesDir.resolve(baseName)
            var n = 1
            while (target.exists()) {
                target = savesDir.resolve("$baseName ($n)")
                n++
            }
            target.mkdirs()

            // 5) 추출
            var extractedFiles = 0
            ZipFile(tmpZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name

                    // 잡파일 스킵
                    if (name.startsWith("__MACOSX/")) continue
                    if (name.endsWith("/.DS_Store") || name == ".DS_Store") continue
                    if (name.contains("..")) continue   // path traversal 방어

                    val rel = if (worldRoot.isEmpty()) name
                    else {
                        if (!name.startsWith(worldRoot)) continue
                        name.removePrefix(worldRoot)
                    }
                    if (rel.isEmpty() || rel.endsWith("/")) continue

                    val outFile = target.resolve(rel)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { input.copyTo(it) }
                    }
                    extractedFiles++
                }
            }

            if (extractedFiles == 0) {
                Log.e("TURTLE_LAUNCHER", "🗺️ 추출된 파일이 0개 — 손상된 zip?")
                target.deleteRecursively()
                return@withContext false
            }

            Log.d("TURTLE_LAUNCHER", "🗺️ 맵 설치 완료: ${target.name} ($extractedFiles 파일)")
            true
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "🗺️ 맵 설치 실패: ${e.message}", e)
            false
        } finally {
            tmpZip.delete()
        }
    }

    /**
     * zip 안에서 level.dat 가 위치한 prefix 를 찾는다.
     *  - ""       : zip root 가 곧 월드
     *  - "Name/"  : 한 단계 안
     *  - null     : 못 찾음 (월드 zip 아님)
     * 여러 개면 가장 얕은 것을 채택 (백업 폴더 등 회피).
     */
    private fun findWorldRootInZip(zipFile: File): String? {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            val levelDat = entries
                .filter {
                    !it.isDirectory &&
                            (it.name == "level.dat" || it.name.endsWith("/level.dat"))
                }
                .minByOrNull { it.name.count { c -> c == '/' } }
                ?: return null

            return if (levelDat.name == "level.dat") ""
            else levelDat.name.substringBeforeLast("/level.dat") + "/"
        }
    }

    /**
     * 인스턴스 안의 월드(세이브) 목록. level.dat 가 있는 폴더만 월드로 간주한다.
     * @return 월드 폴더명 리스트 (saves/ 아래 디렉터리명)
     */
    fun listWorlds(instanceDir: File, mcVersion: String): List<String> {
        val savesDir = if (isLegacyVersion(mcVersion))
            instanceDir.resolve(".minecraft/saves")
        else
            instanceDir.resolve("saves")
        if (!savesDir.isDirectory) return emptyList()
        return savesDir.listFiles()
            ?.filter { it.isDirectory && File(it, "level.dat").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * CurseForge 데이터팩 zip 을 받아 특정 월드의 datapacks/ 에 넣는다.
     * 데이터팩은 압축 해제 없이 zip 그대로 둔다 — Minecraft 가 zip 데이터팩을 직접 읽는다.
     * (pack.mcmeta 가 zip 루트에 있어야 정상 인식되며, 그 검증까지 해준다.)
     *
     * @param worldName saves/ 아래 대상 월드 폴더명 (listWorlds 로 고른 것)
     */
    private suspend fun installDatapack(
        file: CurseForgeFile,
        instanceDir: File,
        mcVersion: String,
        worldName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val savesDir = if (isLegacyVersion(mcVersion))
            instanceDir.resolve(".minecraft/saves")
        else
            instanceDir.resolve("saves")
        val worldDir = savesDir.resolve(worldName)
        if (!worldDir.isDirectory) {
            Log.e("TURTLE_LAUNCHER", "📦 대상 월드 없음: $worldName")
            return@withContext false
        }
        val datapacksDir = worldDir.resolve("datapacks")
        datapacksDir.mkdirs()

        val tmpZip = File.createTempFile("datapack-", ".zip", context.cacheDir)
        try {
            // 1) 다운로드
            val url = resolveDownloadUrl(file)
            downloadFile(url, tmpZip, file.fileName)

            // 2) 데이터팩 zip 검증 — pack.mcmeta 가 루트에 있어야 함
            val hasPackMeta = ZipFile(tmpZip).use { zip ->
                zip.entries().asSequence().any { !it.isDirectory && it.name == "pack.mcmeta" }
            }
            if (!hasPackMeta) {
                // 루트가 아닌 한 단계 안에 있는 경우(폴더로 감싼 zip) — 그건 그대로 두면
                // Minecraft 가 인식 못 하므로 실패 처리(추후 재포장 로직 추가 가능).
                val nested = ZipFile(tmpZip).use { zip ->
                    zip.entries().asSequence().any { !it.isDirectory && it.name.endsWith("/pack.mcmeta") }
                }
                if (nested) {
                    Log.e("TURTLE_LAUNCHER", "📦 데이터팩이 폴더로 감싸져 있음(루트에 pack.mcmeta 없음): ${file.fileName}")
                } else {
                    Log.e("TURTLE_LAUNCHER", "📦 pack.mcmeta 없음 — 데이터팩 아님? ${file.fileName}")
                }
                return@withContext false
            }

            // 3) datapacks/ 에 zip 그대로 복사 (충돌 시 번호 붙임)
            val safeName = file.fileName
                .replace(Regex("[^A-Za-z0-9가-힣 _.\\-]"), "_")
                .let { if (it.endsWith(".zip", true)) it else "$it.zip" }
            var target = datapacksDir.resolve(safeName)
            var n = 1
            val base = safeName.substringBeforeLast(".zip")
            while (target.exists()) {
                target = datapacksDir.resolve("$base ($n).zip")
                n++
            }
            tmpZip.copyTo(target, overwrite = false)

            Log.d("TURTLE_LAUNCHER", "📦 데이터팩 설치 완료: ${target.name} → $worldName/datapacks/")
            true
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "📦 데이터팩 설치 실패: ${e.message}", e)
            false
        } finally {
            tmpZip.delete()
        }
    }

    /** 추가 정보 없이 바로 설치 (모드팩/텍스처팩/쉐이더팩 케이스) */
    /**
     * 공통 모델 → CurseForge 모델 재구성(CurseForge 항목 전용).
     * 기존 CurseForge 설치 파이프라인이 CurseForgeMod 를 요구하므로, 라우팅 지점에서만 변환해 넘긴다.
     * latestFiles 등은 설치 시 API 로 다시 조회하므로 비워도 무방.
     */
    private fun cfModFromItem(item: ContentItem): CurseForgeMod = CurseForgeMod(
        id = item.rawId ?: item.id.toIntOrNull() ?: -1,
        name = item.name,
        summary = item.summary,
        downloadCount = item.downloads,
        logo = item.logoUrl?.let { CurseForgeLogo(url = it, thumbnailUrl = it) },
        latestFiles = emptyList(),
        latestFilesIndexes = emptyList(),
        categories = emptyList()
    )

    suspend fun installDirect(
        mod: ContentItem,
        contentType: ContentType,
        fileId: Int? = null,
        mrVersionId: String? = null,
    ) {
        // Modrinth 는 .mrpack 경로로 분기 (모드팩만 우선 지원). 사용자가 버전 선택 다이얼로그에서
        //   고른 버전 id(mrVersionId)가 있으면 그 버전을, 없으면 최신 버전을 받는다.
        if (mod.source == ContentSource.MODRINTH) {
            installModrinthModpack(mod, mrVersionId)
            return
        }
        val cfMod = cfModFromItem(mod)
        if (!beginInstall(mod, "${contentType.label} 설치 중...")) return
        try {
            when (contentType) {
                ContentType.MODPACK -> installModpack(cfMod, fileId)
                else -> {
                    Log.e("TURTLE_LAUNCHER",
                        "❌ $contentType 가 installDirect 로 들어옴 — detailLauncher 분기 확인 필요")
                    _statusMessage.value = "내부 오류: 설치 타겟이 지정되지 않음"
                }
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "설치 실패: ${e.message}", e)
        } finally {
            endInstall()
        }
    }

    /**
     * Modrinth 모드팩 설치.
     *
     * 흐름:
     *  1) 설치할 버전 결정 — [mrVersionId] 가 있으면 그 버전, 없으면 .mrpack 을 가진 최신 버전.
     *  2) 그 버전의 primary .mrpack 을 MrpackInstaller 로 설치 (mods/overrides 전개).
     *  3) ★ manifest 에 명시됐지만 .mrpack 에 파일이 빠진 required 의존성을 찾아 mods/ 에 보강.
     *     (Modrinth 모드팩은 보통 의존성을 동봉하지만, external 표기/누락 케이스를 커버)
     *  4) 공통 마무리(finalizeModpackInstance): 바닐라 MC + 로더 설치 + 메타 저장.
     */
    suspend fun installModrinthModpack(mod: ContentItem, mrVersionId: String? = null) {
            if (!beginInstall(mod, "${mod.name} 모드팩 설치 중...")) return
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val versions = modrinthApi.getVersions(mod.id)
                    // 고른 버전 우선, 없으면 .mrpack(primary) 가진 최신, 그것도 없으면 아무 버전
                    //   ※ ModrinthVersion.id 가 모델에서 다른 이름이면(@SerializedName) 이 한 줄만 맞추면 됨.
                    val version = mrVersionId?.let { id -> versions.firstOrNull { it.id == id } }
                        ?: versions.firstOrNull { v -> v.files.any { it.primary } }
                        ?: versions.firstOrNull()
                    val file = version?.files?.firstOrNull { it.primary } ?: version?.files?.firstOrNull()
                    if (version == null || file == null) {
                        Log.e("TURTLE_LAUNCHER", "❌ Modrinth 모드팩 파일 없음: ${mod.id} (ver=$mrVersionId)")
                        return@withContext null
                    }
                    val instanceId = InstanceManager.modpackId(mod.name)
                    val instanceDir = InstanceManager.instanceDir(context, instanceId)
                    instanceDir.mkdirs()
                    cleanStaleInstanceFilesForReinstall(instanceDir)
                    val installer = MrpackInstaller(
                        baseDir = instanceDir,
                        modrinthApi = modrinthApi,
                        onProgress = { _progress.value = it }
                    )
                    Triple(installer.install(file.url, mod.name), instanceDir, version)
                }
                if (prepared == null) {
                    _statusMessage.value = "❌ ${mod.name} 파일 정보를 가져올 수 없음"
                    return
                }
                val (installResult, instanceDir, rootVersion) = prepared
                if (!installResult.success) {
                    _statusMessage.value = "❌ ${mod.name} 설치 실패: ${installResult.error ?: "알 수 없음"}"
                    return
                }

                // ── 3) 누락 의존성 보강 ──
                //   .mrpack 전개 후 mods/ 를 스캔해, manifest 의 required 의존성 중
                //   아직 설치 안 된 프로젝트를 호환 버전으로 받아 채운다.
                _statusMessage.value = "의존성 확인 중..."
                withContext(Dispatchers.IO) {
                    installMissingMrpackDependencies(
                        rootVersion = rootVersion,
                        instanceDir = instanceDir,
                        mcVersion = installResult.mcVersion,
                        loaderType = installResult.loaderType?.lowercase(),
                    )
                }

                // .mrpack 설치기가 mods/overrides 를 풀고 (mcVersion, loaderType, loaderVersion) 을 줬으니
                //   이후 바닐라 다운로드 + 로더 설치 + 메타 저장은 CurseForge 와 동일한 공통 경로를 탄다.
                finalizeModpackInstance(
                    instanceId = InstanceManager.modpackId(mod.name),
                    instanceDir = instanceDir,
                    displayName = mod.name,
                    mcVersion = installResult.mcVersion,
                    loaderType = installResult.loaderType?.lowercase(),
                    loaderVersion = installResult.loaderVersion,
                    sourceModId = null,   // Modrinth 는 숫자 id 가 없음 (이름 폴백으로 설치표시)
                    iconUrl = mod.logoUrl,
                )
                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
                _statusMessage.value = "✅ ${mod.name} 설치 완료"
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "Modrinth 모드팩 설치 실패: ${e.message}", e)
                _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = e.message)
            } finally {
                endInstall()
            }
    }

    /**
     * Modrinth 모드팩(.mrpack) 설치 후, manifest 에 required 로 명시됐지만 mods/ 에 파일이 없는
     * 의존성을 찾아 보강한다.
     *
     * 동작:
     *  - rootVersion.dependencies 의 required 의존성을 BFS 로 재귀 수집 (resolveModrinthDependencies 재사용)
     *  - 각 의존성 파일이 이미 mods/ 에 있으면 스킵 (파일명 prefix 비교)
     *  - 없으면 mc/loader 호환 버전을 받아 mods/ 에 떨군다
     *
     * 모드팩이 의존성을 이미 동봉한 경우 대부분 여기서 전부 "이미 있음"으로 스킵된다.
     * external 표기(.mrpack 에 URL 만 있고 파일 미동봉)나 manifest 누락 케이스만 실제로 받는다.
     */
    private suspend fun installMissingMrpackDependencies(
        rootVersion: ModrinthVersion,
        instanceDir: File,
        mcVersion: String,
        loaderType: String?,
    ) {
        val depFiles = resolveModrinthDependencies(rootVersion, mcVersion, loaderType)
        if (depFiles.isEmpty()) {
            Log.d("TURTLE_LAUNCHER", "🩹 mrpack 의존성: 추가로 받을 항목 없음")
            return
        }

        val modsDir = (if (isLegacyVersion(mcVersion)) instanceDir.resolve(".minecraft/mods")
        else instanceDir.resolve("mods")).also { it.mkdirs() }

        // 이미 깔린 jar 들의 prefix 집합 — 같은 모드 다른 파일명이어도 prefix 로 중복 차단
        val installedPrefixes = (modsDir.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?.map { extractModFilePrefix(it.name).lowercase() }
            ?.toMutableSet()) ?: mutableSetOf()

        var added = 0
        depFiles.forEach { f ->
            val prefix = extractModFilePrefix(f.filename).lowercase()
            if (prefix in installedPrefixes) {
                Log.d("TURTLE_LAUNCHER", "🩹 의존성 이미 존재(스킵): ${f.filename}")
                return@forEach
            }
            val outFile = modsDir.resolve(f.filename)
            if (outFile.exists() && outFile.length() == f.size && f.size > 0) {
                installedPrefixes += prefix
                return@forEach
            }
            try {
                _statusMessage.value = "의존성 ${f.filename} 다운로드 중..."
                downloadFile(f.url, outFile, f.filename)
                if (outFile.exists() && outFile.length() > 0) {
                    installedPrefixes += prefix
                    added++
                    Log.d("TURTLE_LAUNCHER", "🩹 누락 의존성 보강: ${f.filename}")
                } else {
                    Log.w("TURTLE_LAUNCHER", "🩹 의존성 검증 실패: ${f.filename}")
                }
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "🩹 의존성 다운로드 실패: ${f.filename} — ${e.message}", e)
            }
        }
        Log.d("TURTLE_LAUNCHER", "🩹 mrpack 의존성 보강 완료: ${added}개 추가 (후보 ${depFiles.size})")
    }

    /**
     * Fabric 인스턴스에 모드를 추가할 때 mods/ 에 Fabric API 가 없다면 자동 설치.
     * CurseForge 의 메타가 Fabric API 를 required 로 표기 안 한 경우를 커버한다.
     *
     * NeoForge / Forge / Vanilla 인스턴스에는 호출 안 함:
     *  - NeoForge / Forge 는 API 가 로더에 내장
     *  - Vanilla 는 Fabric 자체가 없으니 무의미
     */
    private suspend fun ensureFabricApiInstalled(
        instanceDir: File,
        mcVersion: String,
    ) {
        val modsDir = if (isLegacyVersion(mcVersion))
            instanceDir.resolve(".minecraft/mods")
        else
            instanceDir.resolve("mods")
        modsDir.mkdirs()

        val jars = modsDir.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?: emptyList()

        // 이미 Fabric API 가 깔려있는지 — 파일명 prefix 로 검출
        //  - fabric-api-0.92.0+1.20.1.jar  → prefix "fabric-api"
        //  - fabric-api-base-0.4.x.jar      → 별개 모듈, 본체 아님
        val hasFabricApi = jars.any { f ->
            val prefix = extractModFilePrefix(f.name).lowercase()
            // 정확히 "fabric-api" 만 본체로 인정. fabric-api-base, fabric-api-lookup 등은 모듈
            prefix == "fabric-api"
        }
        if (hasFabricApi) {
            Log.d("TURTLE_LAUNCHER", "🩹 Fabric API 이미 설치됨 — 스킵")
            return
        }

        Log.d("TURTLE_LAUNCHER", "🩹 Fabric API 없음 → 자동 설치 (mc=$mcVersion)")

        val file = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(FABRIC_API_MOD_ID, mcVersion, "fabric")
        } ?: run {
            Log.w("TURTLE_LAUNCHER", "🩹 Fabric API: mc=$mcVersion 호환 빌드 없음 — 스킵")
            return
        }

        val outFile = modsDir.resolve(file.fileName)
        if (outFile.exists() && outFile.length() == file.fileLength && file.fileLength > 0) {
            Log.d("TURTLE_LAUNCHER", "🩹 Fabric API 동일 파일 존재 — 스킵")
            return
        }
        try {
            val url = resolveDownloadUrl(file)
            withContext(Dispatchers.IO) { downloadFile(url, outFile, file.fileName) }
            Log.d("TURTLE_LAUNCHER", "🩹 Fabric API 자동 설치 완료: ${file.fileName}")
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "🩹 Fabric API 다운로드 실패: ${e.message}", e)
        }
    }

    /**
     * 모드팩 설치가 끝난 뒤 mods/ 폴더를 스캔해 Sodium 본체가 있으면 Podium 을 자동으로 끼워넣는다.
     *
     * 트리거 조건:
     *  - loader 가 fabric 또는 neoforge 일 것 (Podium 은 Forge / vanilla 미지원)
     *  - mods/ 에 Sodium 본체 jar 가 있을 것 (Sodium Extra / Reese's Sodium Options / Indium 등은 제외)
     *  - 이미 podium*.jar 가 있다면 스킵 (모드팩이 이미 포함시킨 경우)
     *
     * Podium 빌드 선택은 mc + loader 정확 매칭 → loader 만 매칭 → 최신 무조건 폴백 (Podium 은
     * 호환성 패치 모드라 mc 버전 잠그지 않아도 동작하는 경우가 대부분).
     */
    private suspend fun installPodiumIfSodiumInModpack(
        instanceDir: File,
        mcVersion: String,
        loaderType: String?,
    ) {
        val normalizedLoader = loaderType?.lowercase()
        if (normalizedLoader !in setOf("fabric", "neoforge")) {
            Log.d("TURTLE_LAUNCHER", "🩹 Podium augment 스킵 — loader=$normalizedLoader (Fabric/NeoForge 만 지원)")
            return
        }

        val modsDir = instanceDir.resolve("mods")
        if (!modsDir.exists()) return

        val jars = modsDir.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?: return

        // Sodium 본체만 트리거 — addon 류는 무시
        // 파일명 prefix 가 정확히 "sodium" / "sodium-fabric" / "sodium-neoforge" 중 하나일 때만
        val sodiumPrefixes = setOf("sodium", "sodium-fabric", "sodium-neoforge","embeddium")
        val sodiumJar = jars.firstOrNull { f ->
            extractModFilePrefix(f.name).lowercase() in sodiumPrefixes
        }
        if (sodiumJar == null) {
            Log.d("TURTLE_LAUNCHER", "🩹 모드팩 mods/ 에 Sodium 본체 없음 — Podium augment 불필요")
            return
        }

        val hasPodium = jars.any { it.name.startsWith("podium", ignoreCase = true) }
        if (hasPodium) {
            Log.d("TURTLE_LAUNCHER", "🩹 모드팩에 Podium 이미 포함됨 (${jars.first { it.name.startsWith("podium", true) }.name}) — 스킵")
            return
        }

        Log.d("TURTLE_LAUNCHER",
            "🩹 모드팩 Sodium 감지(${sodiumJar.name}) → Podium 자동 추가 시도 (mc=$mcVersion, loader=$normalizedLoader)")

        val podiumFile = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(PODIUM_MOD_ID, mcVersion, normalizedLoader)
                ?: fetchLatestFileForVersion(PODIUM_MOD_ID, gameVersion = null, loaderType = normalizedLoader)?.also {
                    Log.w("TURTLE_LAUNCHER",
                        "🩹 Podium: mc=$mcVersion 매칭 실패 → loader=$normalizedLoader 최신(${it.fileName})으로 폴백")
                }
                ?: fetchLatestFileForVersion(PODIUM_MOD_ID, gameVersion = null, loaderType = null)?.also {
                    Log.w("TURTLE_LAUNCHER",
                        "🩹 Podium: loader 매칭도 실패 → 가장 최신(${it.fileName})으로 폴백")
                }
        } ?: run {
            Log.w("TURTLE_LAUNCHER", "🩹 Podium 호환 파일 못 찾음 — 스킵")
            return
        }

        val outFile = modsDir.resolve(podiumFile.fileName)
        if (outFile.exists() && outFile.length() == podiumFile.fileLength && podiumFile.fileLength > 0) {
            Log.d("TURTLE_LAUNCHER", "🩹 Podium 이미 같은 파일 존재 — 스킵")
            return
        }
        try {
            val url = resolveDownloadUrl(podiumFile)
            withContext(Dispatchers.IO) { downloadFile(url, outFile, podiumFile.fileName) }
            Log.d("TURTLE_LAUNCHER", "🩹 Podium 자동 설치 완료: ${podiumFile.fileName}")
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "🩹 Podium 다운로드 실패: ${e.message}", e)
        }
    }

    /**
     * Sodium 이 설치 대상에 있고, Podium 이 아직 없으면 Podium 도 같이 받도록 보강.
     * Sodium 의 PojavLauncher 차단을 무력화해주는 호환성 패치.
     */
    private fun augmentWithPodiumIfSodium(
        items: List<Pair<CurseForgeMod, CurseForgeFile>>,
        mcVersion: String,
        loaderType: String?
    ): List<Pair<CurseForgeMod, CurseForgeFile>> {
        val hasSodium = items.any { (m, _) ->
            m.name.equals("Sodium", ignoreCase = true) ||
                    m.name.startsWith("Sodium", ignoreCase = true) && !m.name.contains("Extra", true)
        }
        if (!hasSodium) return items
        if (items.any { it.first.id == PODIUM_MOD_ID }) return items  // 이미 포함
        if (loaderType?.lowercase() != "fabric" && loaderType?.lowercase() != "neoforge") {
            // Podium 은 Fabric/NeoForge 만 지원
            return items
        }

        val podiumMod = fetchModInfo(PODIUM_MOD_ID) ?: return items

        // ── Podium 은 mcVersion 정확 매칭 실패 시 최신 빌드로 폴백 ──
        //   호환성 패치 모드라 굳이 버전 잠그지 않아도 동작하는 경우가 대부분이라
        //   여기서만 예외적으로 "최신 아무거나"를 허용한다.
        //   1) mc + loader 정확 매칭
        //   2) loader 만 맞추고 최신
        //   3) 그것마저 없으면 아무거나 최신
        val podiumFile = fetchLatestFileForVersion(PODIUM_MOD_ID, mcVersion, loaderType)
            ?: fetchLatestFileForVersion(PODIUM_MOD_ID, gameVersion = null, loaderType = loaderType)?.also {
                Log.w("TURTLE_LAUNCHER",
                    "🩹 Podium: mc=$mcVersion 호환 파일 없음 → loader=$loaderType 최신(${it.fileName})으로 폴백")
            }
            ?: fetchLatestFileForVersion(PODIUM_MOD_ID, gameVersion = null, loaderType = null)?.also {
                Log.w("TURTLE_LAUNCHER",
                    "🩹 Podium: loader=$loaderType 매칭도 실패 → 가장 최신 빌드(${it.fileName})로 폴백")
            }
            ?: run {
                Log.w("TURTLE_LAUNCHER", "Podium 파일 자체를 못 찾음 — 스킵")
                return items
            }

        Log.d("TURTLE_LAUNCHER", "🩹 Sodium 감지 → Podium 자동 추가: ${podiumFile.fileName}")
        return items + (podiumMod to podiumFile)
    }

    private suspend fun addContentToInstance(
        mod: CurseForgeMod,
        instanceId: String,
        contentType: ContentType,
        worldName: String? = null,   // 데이터팩 설치 대상 월드 (DATAPACK 일 때만 사용)
    ): Boolean {
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir)
            ?: throw IllegalStateException("인스턴스 메타 없음: $instanceId")

        val loaderFilter = if (contentType == ContentType.MOD) meta.loaderType else null
        Log.d("TURTLE_LAUNCHER",
            "addContentToInstance: mod=${mod.id} type=$contentType " +
                    "instance=$instanceId mc=${meta.mcVersion} loaderFilter=$loaderFilter world=$worldName")

        // ── 1) 메인 파일 결정 ─────────────────────────────────────────
        val rootFile = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(mod.id, meta.mcVersion, loaderFilter)
        } ?: run {
            Log.w("TURTLE_LAUNCHER", "❌ ${mod.name} — MC ${meta.mcVersion} 호환 파일 없음")
            _statusMessage.value = "${mod.name} — MC ${meta.mcVersion} 호환 파일 없음"
            _errorDialogMessage.value =
                "‘${mod.name}’ 는 이 인스턴스(MC ${meta.mcVersion}" +
                        (meta.loaderType?.let { " · $it" } ?: "") + ")에 맞는 버전이 없습니다.\n" +
                        "다른 인스턴스를 고르거나, 호환되는 마인크래프트 버전으로 새 인스턴스를 만들어 주세요."
            return false
        }

        // ── 1.5) MC 버전 재검증 (방어) ─────────────────────────────────
        //   서버/클라 필터가 새더라도, 받은 파일이 인스턴스의 정확한 MC 버전을
        //   gameVersions 에 실제로 포함하는지 최종 확인한다. MOD 에만 엄격 적용
        //   (리소스팩/셰이더/월드/데이터팩은 버전 너그럽거나 별도 경로).
        if (contentType == ContentType.MOD) {
            val fileMcVersions = rootFile.gameVersions.filter {
                it.matches(Regex("""\d+\.\d+(\.\d+)?"""))
            }
            if (fileMcVersions.isNotEmpty() && meta.mcVersion !in fileMcVersions) {
                Log.w("TURTLE_LAUNCHER",
                    "❌ ${mod.name} 버전 불일치 — 인스턴스 MC ${meta.mcVersion}, " +
                            "파일 지원 ${fileMcVersions.joinToString()} (${rootFile.fileName})")
                _statusMessage.value = "${mod.name} — MC ${meta.mcVersion} 비호환"
                _errorDialogMessage.value =
                    "‘${mod.name}’ 는 이 인스턴스(MC ${meta.mcVersion})와 버전이 맞지 않습니다.\n" +
                            "이 모드가 지원하는 버전: ${fileMcVersions.joinToString(", ")}\n" +
                            "해당 버전의 인스턴스를 고르거나 새로 만들어 주세요."
                return false
            }
        }

        if (contentType == ContentType.WORLD) {
            _statusMessage.value = "맵 설치 중..."
            return installWorld(rootFile, instanceDir, meta.mcVersion)
        }

        if (contentType == ContentType.DATAPACK) {
            if (worldName.isNullOrBlank()) {
                Log.e("TURTLE_LAUNCHER", "📦 데이터팩 설치인데 대상 월드가 지정되지 않음")
                _statusMessage.value = "데이터팩을 넣을 월드를 선택해주세요."
                return false
            }
            _statusMessage.value = "데이터팩 설치 중..."
            return installDatapack(rootFile, instanceDir, meta.mcVersion, worldName)
        }

        // ── 2) MOD 타입이면 의존성 재귀 해결 ─────────────────────────
        val deps = if (contentType == ContentType.MOD) {
            withContext(Dispatchers.IO) {
                resolveDependencies(rootFile, meta.mcVersion, loaderFilter)
            }
        } else emptyList()

        val withPodium = withContext(Dispatchers.IO) {
            augmentWithPodiumIfSodium(listOf(mod to rootFile) + deps, meta.mcVersion, loaderFilter)
        }
        val allItems = withPodium   // 기존: val allItems = listOf(mod to rootFile) + deps

        Log.d("TURTLE_LAUNCHER",
            "📦 설치 대상 ${allItems.size}개 (메인 1 + 의존성 ${deps.size})")
        deps.forEach { (m, f) ->
            Log.d("TURTLE_LAUNCHER", "  ↳ ${m.name} → ${f.fileName} (rt=${f.releaseType})")
        }

        // ── 3) 출력 디렉토리 결정 ────────────────────────────────────
        val subDir = when (contentType) {
            ContentType.MOD          -> "mods"
            ContentType.TEXTURE_PACK -> "resourcepacks"
            ContentType.SHADER_PACK  -> "shaderpacks"
            ContentType.MODPACK      -> "mods"
            ContentType.WORLD        -> "saves"
            ContentType.DATAPACK     -> "saves"   // 실제로는 위에서 early-return (월드별 처리)
        }
        val baseDir = if (isLegacyVersion(meta.mcVersion))
            instanceDir.resolve(".minecraft") else instanceDir
        val outDir = baseDir.resolve(subDir).also { it.mkdirs() }

        // ── 4) 순차 다운로드 (충돌 jar 정리 → 다운로드) ──────────────
        var allOk = true
        allItems.forEachIndexed { idx, (m, f) ->
            _statusMessage.value = "[${idx + 1}/${allItems.size}] ${m.name} 다운로드 중..."

            // 같은 prefix의 다른 버전 jar 정리 (이번에 받을 파일과 정확히 같은 이름은 보존)
            if (contentType == ContentType.MOD) {
                withContext(Dispatchers.IO) { removeConflictingJars(outDir, f.fileName) }
            }

            val outFile = outDir.resolve(f.fileName)
            if (outFile.exists() && outFile.length() == f.fileLength && f.fileLength > 0) {
                Log.d("TURTLE_LAUNCHER", "  → 이미 동일 파일 존재, 스킵: ${f.fileName}")
                return@forEachIndexed
            }

            val downloadUrl = resolveDownloadUrl(f)
            try {
                withContext(Dispatchers.IO) {
                    downloadFile(downloadUrl, outFile, f.fileName)
                }
                if (!outFile.exists() || outFile.length() == 0L) {
                    Log.e("TURTLE_LAUNCHER", "  ❌ 검증 실패: ${outFile.absolutePath}")
                    allOk = false
                } else {
                    Log.d("TURTLE_LAUNCHER",
                        "  ✅ ${f.fileName} (${outFile.length()}B)")
                }
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "  ❌ 예외: ${f.fileName} — ${e.message}", e)
                allOk = false
            }
        }

        // ── 5) 텍스처/쉐이더는 메인 파일에만 활성화 ──────────────────
        if (allOk) {
            withContext(Dispatchers.IO) {
                when (contentType) {
                    ContentType.TEXTURE_PACK -> enableResourcePack(baseDir, meta.mcVersion, rootFile.fileName)
                    ContentType.SHADER_PACK  -> enableShaderPack(baseDir, rootFile.fileName)
                    else -> { }
                }
            }
        }

        // ── 7) Fabric 인스턴스에 모드 설치 → Fabric API 보장 ──
        if (allOk && contentType == ContentType.MOD && meta.loaderType?.lowercase() == "fabric") {
            ensureFabricApiInstalled(instanceDir, meta.mcVersion)
        }

        return allOk
    }

    /**
     * Modrinth 개별 컨텐츠를 인스턴스에 추가 (CurseForge addContentToInstance 의 Modrinth 판).
     *
     * Modrinth 버전은 다운로드 URL 을 직접 들고 있어 파일 메타 재조회가 필요 없다.
     *  - MOD: loader/MC 호환 버전 선택 + required 의존성 재귀(프로젝트 단위)
     *  - TEXTURE/SHADER: 호환 버전 선택 후 options.txt / iris.properties 활성화
     *  - DATAPACK: 대상 월드의 datapacks/ 에 zip 투입
     *  - WORLD: Modrinth 엔 사실상 없음 → 미지원
     */
    private suspend fun addModrinthContentToInstance(
        item: ContentItem,
        instanceId: String,
        contentType: ContentType,
        worldName: String? = null,
    ): Boolean {
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir)
            ?: throw IllegalStateException("인스턴스 메타 없음: $instanceId")

        val loaderFilter = if (contentType == ContentType.MOD) meta.loaderType?.lowercase() else null
        Log.d("TURTLE_LAUNCHER",
            "addModrinthContentToInstance: project=${item.id} type=$contentType " +
                    "instance=$instanceId mc=${meta.mcVersion} loaderFilter=$loaderFilter world=$worldName")

        // ── 1) 호환 버전 선택 ─────────────────────────────────────────
        val rootVersion = withContext(Dispatchers.IO) {
            pickModrinthVersion(item.id, meta.mcVersion, loaderFilter)
        } ?: run {
            Log.w("TURTLE_LAUNCHER", "❌ ${item.name} — MC ${meta.mcVersion} 호환 Modrinth 버전 없음")
            _statusMessage.value = "${item.name} — MC ${meta.mcVersion} 호환 버전 없음"
            _errorDialogMessage.value =
                "‘${item.name}’ 는 이 인스턴스(MC ${meta.mcVersion}" +
                        (meta.loaderType?.let { " · $it" } ?: "") + ")에 맞는 버전이 없습니다.\n" +
                        "다른 인스턴스를 고르거나, 호환되는 마인크래프트 버전으로 새 인스턴스를 만들어 주세요."
            return false
        }
        val rootFile = rootVersion.files.firstOrNull { it.primary } ?: rootVersion.files.firstOrNull()
        ?: run {
            _statusMessage.value = "${item.name} — 다운로드 파일 없음"
            return false
        }

        // ── 1.5) MC 버전 재검증 (방어) ─────────────────────────────────
        //   pickModrinthVersion 이 서버 필터를 거치지만, 받은 버전이 인스턴스의 정확한
        //   MC 버전을 gameVersions 에 실제로 포함하는지 최종 확인. MOD 에만 엄격 적용.
        if (contentType == ContentType.MOD) {
            val verMcVersions = rootVersion.gameVersions.filter {
                it.matches(Regex("""\d+\.\d+(\.\d+)?"""))
            }
            if (verMcVersions.isNotEmpty() && meta.mcVersion !in verMcVersions) {
                Log.w("TURTLE_LAUNCHER",
                    "❌ ${item.name} 버전 불일치 — 인스턴스 MC ${meta.mcVersion}, " +
                            "버전 지원 ${verMcVersions.joinToString()} (${rootFile.filename})")
                _statusMessage.value = "${item.name} — MC ${meta.mcVersion} 비호환"
                _errorDialogMessage.value =
                    "‘${item.name}’ 는 이 인스턴스(MC ${meta.mcVersion})와 버전이 맞지 않습니다.\n" +
                            "이 모드가 지원하는 버전: ${verMcVersions.joinToString(", ")}\n" +
                            "해당 버전의 인스턴스를 고르거나 새로 만들어 주세요."
                return false
            }
        }

        if (contentType == ContentType.WORLD) {
            _statusMessage.value = "Modrinth 는 맵(월드) 설치를 지원하지 않습니다."
            return false
        }

        if (contentType == ContentType.DATAPACK) {
            if (worldName.isNullOrBlank()) {
                _statusMessage.value = "데이터팩을 넣을 월드를 선택해주세요."
                return false
            }
            _statusMessage.value = "데이터팩 설치 중..."
            return installModrinthDatapack(rootFile.url, rootFile.filename, instanceDir, meta.mcVersion, worldName)
        }

        // ── 2) MOD 면 required 의존성 재귀 해결 (프로젝트 단위, 사이클 보호) ──
        val items = mutableListOf(rootFile)
        if (contentType == ContentType.MOD) {
            val resolved = withContext(Dispatchers.IO) {
                resolveModrinthDependencies(rootVersion, meta.mcVersion, loaderFilter)
            }
            items.addAll(resolved)
        }

        // ── 3) 출력 디렉토리 결정 ────────────────────────────────────
        val subDir = when (contentType) {
            ContentType.MOD          -> "mods"
            ContentType.TEXTURE_PACK -> "resourcepacks"
            ContentType.SHADER_PACK  -> "shaderpacks"
            else                     -> "mods"
        }
        val baseDir = if (isLegacyVersion(meta.mcVersion))
            instanceDir.resolve(".minecraft") else instanceDir
        val outDir = baseDir.resolve(subDir).also { it.mkdirs() }

        // ── 4) 순차 다운로드 ─────────────────────────────────────────
        var allOk = true
        items.forEachIndexed { idx, f ->
            _statusMessage.value = "[${idx + 1}/${items.size}] ${f.filename} 다운로드 중..."
            if (contentType == ContentType.MOD) {
                withContext(Dispatchers.IO) { removeConflictingJars(outDir, f.filename) }
            }
            val outFile = outDir.resolve(f.filename)
            if (outFile.exists() && outFile.length() == f.size && f.size > 0) {
                Log.d("TURTLE_LAUNCHER", "  → 이미 동일 파일 존재, 스킵: ${f.filename}")
                return@forEachIndexed
            }
            try {
                withContext(Dispatchers.IO) { downloadFile(f.url, outFile, f.filename) }
                if (!outFile.exists() || outFile.length() == 0L) {
                    Log.e("TURTLE_LAUNCHER", "  ❌ 검증 실패: ${outFile.absolutePath}")
                    allOk = false
                } else {
                    Log.d("TURTLE_LAUNCHER", "  ✅ ${f.filename} (${outFile.length()}B)")
                }
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "  ❌ 예외: ${f.filename} — ${e.message}", e)
                allOk = false
            }
        }

        // ── 5) 텍스처/쉐이더는 메인 파일에만 활성화 ──────────────────
        if (allOk) {
            withContext(Dispatchers.IO) {
                when (contentType) {
                    ContentType.TEXTURE_PACK -> enableResourcePack(baseDir, meta.mcVersion, rootFile.filename)
                    ContentType.SHADER_PACK  -> enableShaderPack(baseDir, rootFile.filename)
                    else -> { }
                }
            }
        }

        // ── 6) Fabric 인스턴스 + 모드 → Fabric API 보장 ──
        if (allOk && contentType == ContentType.MOD && meta.loaderType?.lowercase() == "fabric") {
            ensureFabricApiInstalled(instanceDir, meta.mcVersion)
        }

        return allOk
    }

    /**
     * Modrinth 프로젝트에서 (mcVersion, loader) 호환 버전 1개 선택.
     * release > beta > alpha 우선, 그 다음 최신(date_published) 순.
     */
    private fun pickModrinthVersion(
        projectId: String,
        mcVersion: String,
        loaderFilter: String?,   // "fabric"/"forge"/"neoforge" 또는 null(로더 무관)
    ): ModrinthVersion? {
        val loaders = loaderFilter?.let { listOf(it) }
        val versions = modrinthApi.getVersions(projectId, loaders = loaders, gameVersions = listOf(mcVersion))
        if (versions.isEmpty()) return null
        val rank = mapOf("release" to 0, "beta" to 1, "alpha" to 2)
        return versions.sortedWith(
            compareBy<ModrinthVersion> { rank[it.versionType] ?: 3 }
                .thenByDescending { it.datePublished ?: "" }
        ).firstOrNull()
    }

    /**
     * required 의존성을 프로젝트 단위로 재귀 수집. version_id 가 명시되면 그 버전, 아니면 호환 최신.
     * 사이클/중복은 방문한 projectId 집합으로 차단.
     */
    private fun resolveModrinthDependencies(
        root: ModrinthVersion,
        mcVersion: String,
        loaderFilter: String?,
    ): List<ModrinthVersionFile> {
        val out = mutableListOf<ModrinthVersionFile>()
        val visited = mutableSetOf(root.projectId)
        val queue = ArrayDeque(root.dependencies.filter { it.dependencyType == "required" })
        var guard = 0
        while (queue.isNotEmpty() && guard < 64) {
            guard++
            val dep = queue.removeFirst()
            val pid = dep.projectId ?: continue
            if (pid in visited) continue
            visited.add(pid)

            val depVersion = dep.versionId?.let { modrinthApi.getVersion(it) }
                ?: pickModrinthVersion(pid, mcVersion, loaderFilter)
                ?: continue

            val f = depVersion.files.firstOrNull { it.primary } ?: depVersion.files.firstOrNull() ?: continue
            out.add(f)
            // 의존성의 의존성도 따라간다
            queue.addAll(depVersion.dependencies.filter { it.dependencyType == "required" && it.projectId !in visited })
        }
        return out
    }

    /**
     * Modrinth 데이터팩 zip → 대상 월드의 datapacks/ 에 투입 (URL/파일명 직접).
     */
    private suspend fun installModrinthDatapack(
        url: String,
        fileName: String,
        instanceDir: File,
        mcVersion: String,
        worldName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val savesDir = if (isLegacyVersion(mcVersion))
            instanceDir.resolve(".minecraft/saves") else instanceDir.resolve("saves")
        val worldDir = savesDir.resolve(worldName)
        if (!worldDir.isDirectory) {
            Log.e("TURTLE_LAUNCHER", "📦 대상 월드 없음: $worldName")
            return@withContext false
        }
        val datapacksDir = worldDir.resolve("datapacks").also { it.mkdirs() }
        val outFile = datapacksDir.resolve(fileName)
        try {
            downloadFile(url, outFile, fileName)
            val ok = outFile.exists() && outFile.length() > 0
            if (ok) Log.d("TURTLE_LAUNCHER", "✅ 데이터팩 설치: $fileName → $worldName")
            ok
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "데이터팩 설치 실패: ${e.message}", e)
            false
        }
    }

    // ContentPackBrowserActivity.kt 의 private 멤버로 추가

    /**
     * options.txt 의 resourcePacks 항목에 [packFileName] 을 등록한다.
     * - 1.13+ 는 "file/" 접두사 필요, 1.12 이하는 파일명 그대로.
     * - 이미 존재하면 추가하지 않음.
     * - options.txt 없으면 기본값으로 새로 만든다.
     */
    private fun enableResourcePack(baseDir: File, mcVersion: String, packFileName: String) {
        val optionsFile = File(baseDir, "options.txt")
        val packToken = if (isLegacyVersion(mcVersion)) packFileName else "file/$packFileName"

        val lines: MutableList<String> = if (optionsFile.exists())
            optionsFile.readLines().toMutableList()
        else mutableListOf("renderDistance:8", "graphicsMode:0")

        val idx = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        if (idx >= 0) {
            val raw = lines[idx].substringAfter("resourcePacks:")
            val current = parseRpList(raw).toMutableList()
            if (packToken !in current) current.add(packToken)        // 맨 뒤 = 우선순위 최상
            lines[idx] = "resourcePacks:" + serializeRpList(current)
        } else {
            lines.add("resourcePacks:" + serializeRpList(listOf("vanilla", packToken)))
        }
        if (lines.none { it.startsWith("incompatibleResourcePacks:") }) {
            lines.add("incompatibleResourcePacks:[]")
        }

        optionsFile.writeText(lines.joinToString("\n"))
        Log.d("TURTLE_LAUNCHER", "📝 options.txt 갱신: $packToken (${optionsFile.absolutePath})")
    }

    /**
     * Iris/Oculus 의 config/iris.properties 에 [shaderFileName] 을 셋한다.
     * Iris/Oculus 가 설치되지 않은 인스턴스에서는 단순히 무시되는 hint 일 뿐 — 해는 없음.
     */
    private fun enableShaderPack(baseDir: File, shaderFileName: String) {
        val irisConfig = File(baseDir, "config/iris.properties")
        irisConfig.parentFile?.mkdirs()

        val survivors = if (irisConfig.exists())
            irisConfig.readLines().filter {
                !it.startsWith("shaderPack=") && !it.startsWith("shaders.enabled=")
            }
        else emptyList()

        val merged = survivors + listOf(
            "shaders.enabled=true",
            "shaderPack=$shaderFileName"
        )
        irisConfig.writeText(merged.joinToString("\n"))
        Log.d("TURTLE_LAUNCHER", "📝 iris.properties 갱신: $shaderFileName")
    }

// ── options.txt 의 resourcePacks 값 파싱/직렬화 ──
//   resourcePacks:["vanilla","file/Faithful.zip"]   <- 이런 포맷

    private fun parseRpList(raw: String): List<String> {
        val t = raw.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) return emptyList()
        val inner = t.substring(1, t.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        // "a","b" → [a, b]. 단순 split — 항목 안에 쉼표 들어갈 일 없음.
        return inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    private fun serializeRpList(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }

    suspend fun installToExistingInstance(
        mod: ContentItem,
        instanceId: String,
        contentType: ContentType,
        worldName: String? = null,   // 데이터팩일 때 대상 월드
    ) {
        if (mod.source == ContentSource.MODRINTH) {
            if (!beginInstall(mod, "${mod.name} → 인스턴스($instanceId) 설치 중...")) return
            try {
                addModrinthContentToInstance(mod, instanceId, contentType, worldName)
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "Modrinth 기존 인스턴스 설치 실패: ${e.message}", e)
            } finally {
                endInstall()
            }
            return
        }
        val cfMod = cfModFromItem(mod)
        if (!beginInstall(mod, "${mod.name} → 인스턴스($instanceId) 설치 중...")) return
        try {
            addContentToInstance(cfMod, instanceId, contentType, worldName)
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "기존 인스턴스 설치 실패: ${e.message}", e)
        } finally {
            endInstall()
        }
    }

    suspend fun installToNewInstance(
        mod: ContentItem,
        mcVersion: String,
        loader: ModLoader?,                 // ← null 이면 바닐라
        contentType: ContentType,
        newToken: String? = null,           // ← 있으면 고유 분리 토큰(UUID8). 같은 버전 중복 생성 허용.
    ) {
        val isModrinth = mod.source == ContentSource.MODRINTH
        val cfMod = if (isModrinth) null else cfModFromItem(mod)
        val loaderName = loader?.displayName ?: "Vanilla"
        if (!beginInstall(mod, "$loaderName $mcVersion 인스턴스 준비 중...")) return
        try {
            val versionEntry = withContext(Dispatchers.IO) {
                VersionRepository().fetchVersionList().firstOrNull { it.id == mcVersion }
            } ?: run {
                Log.e("TURTLE_LAUNCHER", "MC $mcVersion manifest 못 찾음")
                _statusMessage.value = "MC $mcVersion 를 찾을 수 없습니다."
                return
            }

            val instanceId: String = when (loader) {
                null               -> setupVanillaInstance(mcVersion, versionEntry, newToken) ?: return
                ModLoader.FABRIC   -> setupFabricInstance(mcVersion, versionEntry, newToken)  ?: return
                ModLoader.FORGE    -> setupForgeInstance(mcVersion, versionEntry, isNeoForge = false, newToken = newToken) ?: return
                ModLoader.NEOFORGE -> setupForgeInstance(mcVersion, versionEntry, isNeoForge = true,  newToken = newToken) ?: return
            }

            _statusMessage.value = "${mod.name} 다운로드 중..."
            if (isModrinth) {
                addModrinthContentToInstance(mod, instanceId, contentType)
            } else {
                addContentToInstance(cfMod!!, instanceId, contentType)
            }

            // 모드/모드팩이면 콘텐츠 로고를 인스턴스 아이콘으로 저장(로더 기본 아이콘 대신).
            //   (텍스처/쉐이더/월드 등은 별도 아이콘을 입히지 않고 기존 로더/바닐라 아이콘 유지)
            if (contentType == ContentType.MOD || contentType == ContentType.MODPACK) {
                withContext(Dispatchers.IO) {
                    applyContentIconToInstance(instanceId, mod.logoUrl)
                }
            }

            _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "신규 인스턴스 설치 실패: ${e.message}", e)
            _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = e.message)
        } finally {
            endInstall()
        }
    }

    /** 바닐라 인스턴스 생성 또는 재사용. 실패 시 null. */
    private suspend fun setupVanillaInstance(
        mcVersion: String,
        versionEntry: VersionEntry,
        newToken: String? = null,
    ): String? {
        val instanceId = InstanceManager.withToken(InstanceManager.vanillaId(mcVersion), newToken)
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        if (InstanceManager.loadMeta(instanceDir) != null) {
            Log.d("TURTLE_LAUNCHER", "ℹ️ Vanilla 인스턴스 재사용: $instanceId")
            return instanceId
        }

        _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        // 빈 폴더 미리 생성 — 텍스처/쉐이더 떨어뜨릴 곳
        File(instanceDir, "mods").mkdirs()
        File(instanceDir, "resourcepacks").mkdirs()
        File(instanceDir, "shaderpacks").mkdirs()

        val legacyArgs = mcResult.minecraftArguments
            ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

        InstanceManager.saveMeta(context, InstanceMeta(
            id = instanceId,
            // 토큰이 붙은 경우(=새로 만든 별개 인스턴스) 이름에 짧은 토큰을 표기해 구분이 쉽게.
            name = if (newToken.isNullOrBlank()) mcVersion else "$mcVersion ($newToken)",
            type = InstanceType.VANILLA,
            mcVersion = mcVersion,
            mainClass = mcResult.mainClass,
            assetIndexId = mcResult.assetIndexId,
            iconEmoji = "🌿",
            gameArgs = legacyArgs,
        ))
        return instanceId
    }

    /** 새 Fabric 인스턴스를 만들거나 기존 것 반환. 실패 시 null. */
    private suspend fun setupFabricInstance(
        mcVersion: String,
        versionEntry: VersionEntry,
        newToken: String? = null,
    ): String? {
        val fabricApi = FabricMetaAPI()
        val loaderList = withContext(Dispatchers.IO) { fabricApi.listLoaders(mcVersion) }
        val loaderVersion = loaderList.firstOrNull { it.loader.stable }?.loader?.version
            ?: loaderList.firstOrNull()?.loader?.version
            ?: run {
                Log.e("TURTLE_LAUNCHER", "Fabric loader 후보 없음 mc=$mcVersion")
                return null
            }

        val instanceId = InstanceManager.withToken(
            InstanceManager.fabricId(mcVersion, loaderVersion), newToken
        )
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        if (InstanceManager.loadMeta(instanceDir) != null) {
            Log.d("TURTLE_LAUNCHER", "ℹ️ Fabric 인스턴스 재사용: $instanceId")
            return instanceId
        }

        _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        _statusMessage.value = "Fabric $loaderVersion 설치 중..."
        val fr = withContext(Dispatchers.IO) {
            FabricInstaller(instanceDir) { msg, cur, tot ->
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                    current = cur, total = tot, fileName = msg
                )
            }.install(mcVersion, loaderVersion)
        }
        if (!fr.success) {
            Log.e("TURTLE_LAUNCHER", "Fabric 설치 실패: ${fr.error}")
            return null
        }
        File(instanceDir, "mods").mkdirs()

        InstanceManager.saveMeta(context, InstanceMeta(
            id = instanceId,
            name = if (newToken.isNullOrBlank()) "$mcVersion · Fabric $loaderVersion"
                   else "$mcVersion · Fabric $loaderVersion ($newToken)",
            type = InstanceType.FABRIC,
            mcVersion = mcVersion,
            loaderType = "fabric",
            loaderVersion = loaderVersion,
            mainClass = fr.mainClass,
            extraJars = fr.extraJars,
            assetIndexId = mcResult.assetIndexId,
            iconEmoji = "🧵",
            gameJvmArgs = fr.gameJvmArgs,
            gameArgs = fr.gameArgs
        ))
        return instanceId
    }

    /**
     * 새 Forge/NeoForge 인스턴스 셋업. recommended → latest 순으로 자동 선택.
     *
     * 1.13+ 는 install_profile.json 의 processors 단계가 따로 있어
     * 실제 부팅 시 실패할 수 있음 — requiresProcessors 가 true 면 로그로 경고하고
     * 상태 메시지에도 표시.
     */
    private suspend fun setupForgeInstance(
        mcVersion: String,
        versionEntry: VersionEntry,
        isNeoForge: Boolean,
        newToken: String? = null,
    ): String? {
        val loaderList = withContext(Dispatchers.IO) {
            runCatching {
                if (isNeoForge) {
                    NeoForgeMetaAPI().listLoaders(mcVersion)
                } else {
                    ForgeMetaAPI().listLoaders(mcVersion)
                }
            }.getOrDefault(emptyList())
        }
        if (loaderList.isEmpty()) {
            val name = if (isNeoForge) "NeoForge" else "Forge"
            Log.e("TURTLE_LAUNCHER", "$name 후보 없음 mc=$mcVersion")
            _statusMessage.value = "$mcVersion 용 $name 빌드가 없습니다."
            return null
        }
        val forgeVersion = loaderList.firstOrNull { it.recommended }?.forgeVersion
            ?: loaderList.firstOrNull { it.latest }?.forgeVersion
            ?: loaderList.first().forgeVersion

        val loaderType = if (isNeoForge) "neoforge" else "forge"
        val baseForgeId = "${loaderType}_${mcVersion.replace('.', '_')}_${forgeVersion.replace('.', '_')}"
        val instanceId = InstanceManager.withToken(baseForgeId, newToken)
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        if (InstanceManager.loadMeta(instanceDir) != null) {
            Log.d("TURTLE_LAUNCHER", "ℹ️ $loaderType 인스턴스 재사용: $instanceId")
            return instanceId
        }

        // 1) MC 다운로드
        _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        // 2) Forge / NeoForge 설치
        _statusMessage.value = "${if (isNeoForge) "NeoForge" else "Forge"} $forgeVersion 설치 중..."
        val fr = withContext(Dispatchers.IO) {
            ForgeInstaller(instanceDir) { msg, cur, tot ->
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                    current = cur, total = tot, fileName = msg
                )
            }.install(context, mcVersion, forgeVersion, isNeoForge = isNeoForge)
        }
        if (!fr.success) {
            Log.e("TURTLE_LAUNCHER", "Forge 설치 실패: ${fr.error}")
            _statusMessage.value = "Forge 설치 실패: ${fr.error}"
            return null
        }

        if (fr.requiresProcessors) {
            Log.i("TURTLE_LAUNCHER",
                "Forge 1.13+ — 최초 실행 시 별도 빌더 프로세스가 client jar 를 생성합니다(BinaryPatcher 등). " +
                        "시간이 걸릴 수 있습니다.")
            _statusMessage.value = "Modern Forge — 최초 실행 시 client jar 생성이 수행됩니다."
        }
        File(instanceDir, "mods").mkdirs()

        InstanceManager.saveMeta(context, InstanceMeta(
            id = instanceId,
            name = run {
                val base = "$mcVersion · ${if (isNeoForge) "NeoForge" else "Forge"} $forgeVersion"
                if (newToken.isNullOrBlank()) base else "$base ($newToken)"
            },
            type = InstanceType.MODPACK,         // FABRIC 외엔 MODPACK 으로 분류해두는 듯
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = forgeVersion,
            mainClass = fr.mainClass,
            extraJars = fr.extraJars,
            assetIndexId = mcResult.assetIndexId,
            iconEmoji = if (isNeoForge) "🟢" else "🔥",
            gameJvmArgs = fr.gameJvmArgs,
            gameArgs = fr.gameArgs
        ))
        return instanceId
    }

    /**
     * 모드팩을 자체 인스턴스로 설치한다.
     *
     * 흐름:
     *  1) ModPackInstaller — zip 받고 manifest 파싱 → mcVersion / loader 정보 얻고
     *     overrides 풀고 필수 모드들 mods/ 에 다 떨군다.
     *  2) MinecraftDownloader — manifest 의 mcVersion 으로 바닐라 client / libraries / assets 받음
     *  3) 로더 종류에 따라 FabricInstaller / ForgeInstaller 로 로더 본체 설치
     *  4) InstanceMeta 저장 — mainClass / extraJars / gameJvmArgs / gameArgs 까지 채워서
     *     실행 단계에서 추가 작업 없이 바로 부팅 가능하게 한다.
     *
     * 실패하면 phase=ERROR 로 상태 publish 하고 즉시 종료. 부분 성공 시에도 InstanceMeta 는
     * 이미 ModPackInstaller 가 한 번 저장하므로, 사용자가 같은 모드팩을 다시 누르면
     * `loaderType != null` 캐시 체크로 zip 재다운로드는 스킵된다.
     */
    private suspend fun installModpack(mod: CurseForgeMod, fileId: Int? = null) {
        val instanceId = InstanceManager.modpackId(mod.name)
        val instanceDir = InstanceManager.instanceDir(context, instanceId).also { it.mkdirs() }
        cleanStaleInstanceFilesForReinstall(instanceDir)

        // ── 0) 어떤 파일(=어떤 모드팩 버전) 받을지 결정 ─────────────
        //   사용자가 버전 선택 다이얼로그에서 고른 fileId 가 있으면 그 파일을, 없으면 최신 파일.
        val file = withContext(Dispatchers.IO) {
            if (fileId != null) fetchFileById(mod.id, fileId)
                ?: fetchLatestFileForVersion(mod.id, gameVersion = null, loaderType = null)
            else fetchLatestFileForVersion(mod.id, gameVersion = null, loaderType = null)
        } ?: run {
            Log.e("TURTLE_LAUNCHER", "❌ 모드팩 파일 정보 못 가져옴: mod=${mod.id} fileId=$fileId")
            _statusMessage.value = "❌ ${mod.name} 파일 정보를 가져올 수 없음"
            _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = "파일 정보 없음")
            return
        }
        Log.d("TURTLE_LAUNCHER", "📦 모드팩 설치 파일 확정: ${file.displayName} (id=${file.id}, rt=${file.releaseType})")

        // ── 1) 모드팩 zip 다운로드 + manifest 파싱 + overrides + 필수 모드들 ──
        _statusMessage.value = "${mod.name} 모드팩 추출 중..."
        val packResult = withContext(Dispatchers.IO) {
            ModPackInstaller(
                baseDir = instanceDir,
                curseForgeApi = CurseForgeAPI(),
                onProgress = { _progress.value = it }
            ).install(mod, file.id, mcVersionOverride = file.primaryMcVersion())
        }
        if (!packResult.success) {
            Log.e("TURTLE_LAUNCHER", "❌ ModPackInstaller 실패: ${packResult.error}")
            _statusMessage.value = "❌ 모드팩 추출 실패: ${packResult.error}"
            _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = packResult.error)
            return
        }

        val mcVersion = packResult.mcVersion
        val loaderType = packResult.loaderType?.lowercase()
        val loaderVersion = packResult.loaderVersion
        Log.d("TURTLE_LAUNCHER", "📦 모드팩 파싱: mc=$mcVersion, loader=$loaderType $loaderVersion")

        finalizeModpackInstance(
            instanceId = instanceId,
            instanceDir = instanceDir,
            displayName = mod.name,
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            sourceModId = mod.id,
            iconUrl = mod.logo?.url,
        )
    }

    /**
     * 모드팩 설치 공통 마무리 — CurseForge / Modrinth 모두 이 경로를 탄다.
     *
     * 입력: 모드/overrides 가 이미 instanceDir 에 풀린 상태 + (mcVersion, loaderType, loaderVersion).
     * 처리: Sodium→Podium 점검 → 바닐라 MC 다운로드 → 로더 설치 → InstanceMeta 조립/저장.
     *
     * @param sourceModId CurseForge 숫자 id. Modrinth 는 숫자 id 가 없으므로 null.
     */
    private suspend fun finalizeModpackInstance(
        instanceId: String,
        instanceDir: File,
        displayName: String,
        mcVersion: String,
        loaderType: String?,
        loaderVersion: String?,
        sourceModId: Int?,
        iconUrl: String? = null,        // 콘텐츠 로고 URL(CurseForge/Modrinth). 받아서 인스턴스 아이콘으로 저장.
    ) {
        // ── 1.5) Sodium 본체가 모드팩에 있으면 Podium 자동 동봉 ──
        _statusMessage.value = "Sodium 호환 점검 중..."
        installPodiumIfSodiumInModpack(instanceDir, mcVersion, loaderType)

        // ── 2) Mojang manifest 에서 해당 MC 버전 entry 확보 ─────────
        val versionEntry = withContext(Dispatchers.IO) {
            runCatching { VersionRepository().fetchVersionList().firstOrNull { it.id == mcVersion } }
                .getOrNull()
        } ?: run {
            Log.e("TURTLE_LAUNCHER", "❌ MC $mcVersion manifest 없음")
            _statusMessage.value = "❌ MC $mcVersion 매니페스트를 찾을 수 없음"
            _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = "MC manifest 없음")
            return
        }

        // ── 3) 바닐라 MC 다운로드 (인스턴스 dir 안으로) ─────────────
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        // ── 3.5) 모드팩 로고를 인스턴스 아이콘으로 저장 (실패해도 무시) ──
        val iconPath = withContext(Dispatchers.IO) {
            downloadInstanceIcon(iconUrl, instanceDir)
        }

        // ── 4) 로더 설치 + 최종 InstanceMeta 조립 ───────────────────
        val finalMeta: InstanceMeta = when (loaderType) {
            "fabric" -> {
                if (loaderVersion.isNullOrBlank()) {
                    _statusMessage.value = "❌ Fabric loader 버전이 manifest 에 없음"
                    return
                }
                _statusMessage.value = "Fabric $loaderVersion 설치 중..."
                val fr = withContext(Dispatchers.IO) {
                    FabricInstaller(instanceDir) { msg, cur, tot ->
                        _progress.value = DownloadProgress(
                            phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                            current = cur, total = tot, fileName = msg
                        )
                    }.install(mcVersion, loaderVersion)
                }
                if (!fr.success) {
                    Log.e("TURTLE_LAUNCHER", "❌ Fabric 설치 실패: ${fr.error}")
                    _statusMessage.value = "❌ Fabric 설치 실패: ${fr.error}"
                    _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = fr.error)
                    return
                }
                InstanceMeta(
                    id = instanceId,
                    name = displayName,
                    type = InstanceType.MODPACK,
                    mcVersion = mcVersion,
                    loaderType = "fabric",
                    loaderVersion = loaderVersion,
                    mainClass = fr.mainClass,
                    extraJars = fr.extraJars,
                    assetIndexId = mcResult.assetIndexId,
                    iconEmoji = "🧵",
                    gameJvmArgs = fr.gameJvmArgs,
                    gameArgs = fr.gameArgs,
                    sourceModId = sourceModId,
                    iconPath = iconPath,
                )
            }

            "quilt" -> {
                if (loaderVersion.isNullOrBlank()) {
                    _statusMessage.value = "❌ Quilt loader 버전이 manifest 에 없음"
                    return
                }
                _statusMessage.value = "Quilt $loaderVersion 설치 중..."
                val qr = withContext(Dispatchers.IO) {
                    QuiltInstaller(instanceDir) { msg, cur, tot ->
                        _progress.value = DownloadProgress(
                            phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                            current = cur, total = tot, fileName = msg
                        )
                    }.install(mcVersion, loaderVersion)
                }
                if (!qr.success) {
                    Log.e("TURTLE_LAUNCHER", "❌ Quilt 설치 실패: ${qr.error}")
                    _statusMessage.value = "❌ Quilt 설치 실패: ${qr.error}"
                    _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = qr.error)
                    return
                }
                InstanceMeta(
                    id = instanceId,
                    name = displayName,
                    type = InstanceType.MODPACK,
                    mcVersion = mcVersion,
                    loaderType = "quilt",
                    loaderVersion = loaderVersion,
                    mainClass = qr.mainClass,
                    extraJars = qr.extraJars,
                    assetIndexId = mcResult.assetIndexId,
                    iconEmoji = "🧶",
                    gameJvmArgs = qr.gameJvmArgs,
                    gameArgs = qr.gameArgs,
                    sourceModId = sourceModId,
                    iconPath = iconPath,
                )
            }

            "forge", "neoforge" -> {
                if (loaderVersion.isNullOrBlank()) {
                    _statusMessage.value = "❌ $loaderType loader 버전이 manifest 에 없음"
                    return
                }
                val isNeoForge = loaderType == "neoforge"
                val label = if (isNeoForge) "NeoForge" else "Forge"
                _statusMessage.value = "$label $loaderVersion 설치 중..."
                val fr = withContext(Dispatchers.IO) {
                    ForgeInstaller(instanceDir) { msg, cur, tot ->
                        _progress.value = DownloadProgress(
                            phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                            current = cur, total = tot, fileName = msg
                        )
                    }.install(context, mcVersion, loaderVersion, isNeoForge = isNeoForge)
                }
                if (!fr.success) {
                    Log.e("TURTLE_LAUNCHER", "❌ $label 설치 실패: ${fr.error}")
                    _statusMessage.value = "❌ $label 설치 실패: ${fr.error}"
                    _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = fr.error)
                    return
                }
                if (fr.requiresProcessors) {
                    Log.i("TURTLE_LAUNCHER", "ℹ️ Modern $label — 첫 실행 시 별도 빌더 프로세스가 client jar 생성")
                }
                InstanceMeta(
                    id = instanceId,
                    name = displayName,
                    type = InstanceType.MODPACK,
                    mcVersion = mcVersion,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    mainClass = fr.mainClass,
                    extraJars = fr.extraJars,
                    assetIndexId = mcResult.assetIndexId,
                    iconEmoji = if (isNeoForge) "🟢" else "🔥",
                    gameJvmArgs = fr.gameJvmArgs,
                    gameArgs = fr.gameArgs,
                    sourceModId = sourceModId,
                    iconPath = iconPath,
                )
            }

            else -> {
                Log.w("TURTLE_LAUNCHER",
                    "⚠️ 모드팩 loader 식별 실패 (raw=$loaderType) — Vanilla 로 폴백")
                val legacyArgs = mcResult.minecraftArguments
                    ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
                InstanceMeta(
                    id = instanceId,
                    name = displayName,
                    type = InstanceType.MODPACK,
                    mcVersion = mcVersion,
                    mainClass = mcResult.mainClass,
                    assetIndexId = mcResult.assetIndexId,
                    iconEmoji = "📦",
                    gameArgs = legacyArgs,
                    sourceModId = sourceModId,
                    iconPath = iconPath,
                )
            }
        }

        // ── 5) 메타 저장 + 빈 폴더 보장 ─────────────────────────────
        File(instanceDir, "mods").mkdirs()
        File(instanceDir, "resourcepacks").mkdirs()
        File(instanceDir, "shaderpacks").mkdirs()
        InstanceManager.saveMeta(context, finalMeta)

        _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
        _statusMessage.value = "✅ $displayName 설치 완료"
        Log.d("TURTLE_LAUNCHER", "✅ 모드팩 인스턴스 생성 완료: $instanceId (${finalMeta.loaderType ?: "vanilla"})")
    }


    /**
     * 특정 mod의 최신 호환 파일 선택.
     * 정렬 우선순위: release > beta > alpha, 그 다음 id desc (최신).
     * gameVersion이 주어지면 그 버전 문자열이 gameVersions에 정확히 포함된 것만.
     */
    /**
     * 특정 fileId 의 파일 단건 조회. 모드팩 버전 선택에서 고른 파일을 정확히 받기 위함.
     * 엔드포인트: GET /v1/mods/{modId}/files/{fileId}  (CurseForgeResponse<CurseForgeFile>)
     * 실패 시 null → 호출부가 최신 파일로 폴백.
     */
    private fun fetchFileById(modId: Int, fileId: Int): CurseForgeFile? {
        val request = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/files/$fileId")
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@runCatching null
                val type = object : TypeToken<CurseForgeResponse<CurseForgeFile>>() {}.type
                gson.fromJson<CurseForgeResponse<CurseForgeFile>>(body, type).data
            }
        }.onFailure {
            Log.w("TURTLE_LAUNCHER", "파일 단건 조회 실패: mod=$modId file=$fileId — ${it.message}")
        }.getOrNull()
    }

    private fun fetchLatestFileForVersion(
        modId: Int,
        gameVersion: String?,
        loaderType: String?
    ): CurseForgeFile? {
        val url = StringBuilder("https://api.curseforge.com/v1/mods/$modId/files?index=0&pageSize=50")
        if (!gameVersion.isNullOrBlank()) url.append("&gameVersion=").append(gameVersion)
        if (!loaderType.isNullOrBlank()) {
            val modLoaderType = when (loaderType.lowercase()) {
                "forge" -> 1
                "fabric" -> 4
                "quilt" -> 5
                "neoforge" -> 6
                else -> null
            }
            if (modLoaderType != null) url.append("&modLoaderType=").append(modLoaderType)
        }

        val request = Request.Builder()
            .url(url.toString())
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>() {}.type
            return runCatching {
                val files = gson.fromJson<CurseForgeListResponse<CurseForgeFile>>(body, type).data

                // 1) MC 버전 정확 매칭 (e.g. "1.21.1" 만, "1.21"이나 "1.21.2"는 제외)
                val mcMatched = if (!gameVersion.isNullOrBlank())
                    files.filter { it.gameVersions.contains(gameVersion) }
                else files

                // 2) 로더 매칭 검증 (server 필터가 가끔 새는 케이스 대비)
                val loaderMatched = if (!loaderType.isNullOrBlank()) {
                    mcMatched.filter { f ->
                        f.gameVersions.any { it.equals(loaderType, ignoreCase = true) }
                                // 일부 모드는 로더 태그를 안 박음 — 그건 그대로 허용
                                || f.gameVersions.none { it in LOADER_TAGS }
                    }
                } else mcMatched

                // 3) release > beta > alpha, 같은 등급이면 id desc
                loaderMatched
                    .sortedWith(compareBy({ it.releaseType }, { -it.id }))
                    .firstOrNull()
                    .also {
                        Log.d("TURTLE_LAUNCHER",
                            "📋 mod=$modId mc=$gameVersion loader=$loaderType " +
                                    "→ ${it?.fileName ?: "(없음)"} (rt=${it?.releaseType})")
                    }
            }.getOrNull()
        }
    }

    private val LOADER_TAGS = setOf("Forge", "Fabric", "NeoForge", "Quilt")

    private fun fetchModInfo(modId: Int): CurseForgeMod? {
        val request = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId")
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@runCatching null
                val type = object : TypeToken<CurseForgeResponse<CurseForgeMod>>() {}.type
                gson.fromJson<CurseForgeResponse<CurseForgeMod>>(body, type).data
            }
        }.getOrNull()
    }

    /**
     * file 의 RequiredDependency(relationType=3) 들을 같은 mc/loader 로 재귀 해결.
     * @return (mod, file) 쌍 리스트. 중복 modId는 한 번만.
     */
    private fun resolveDependencies(
        rootFile: CurseForgeFile,
        mcVersion: String,
        loaderType: String?,
        visited: MutableSet<Int> = mutableSetOf()
    ): List<Pair<CurseForgeMod, CurseForgeFile>> {
        val out = mutableListOf<Pair<CurseForgeMod, CurseForgeFile>>()
        val required = rootFile.dependencies.filter { it.relationType == 3 }

        for (dep in required) {
            if (!visited.add(dep.modId)) continue

            val depMod = fetchModInfo(dep.modId)
            if (depMod == null) {
                Log.w("TURTLE_LAUNCHER", "  ↳ 의존성 mod 정보 못 받음: id=${dep.modId}")
                continue
            }

            val depFile = fetchLatestFileForVersion(dep.modId, mcVersion, loaderType)
            if (depFile == null) {
                Log.w("TURTLE_LAUNCHER",
                    "  ↳ ${depMod.name} — mc=$mcVersion loader=$loaderType 호환 파일 없음")
                continue
            }

            out += depMod to depFile
            out += resolveDependencies(depFile, mcVersion, loaderType, visited)
        }
        return out
    }

    /**
     * "sodium-fabric-0.8.12-alpha.4+mc1.21.1.jar" → "sodium-fabric"
     * "iris-fabric-1.8.8+mc1.21.1.jar"            → "iris-fabric"
     *
     * jar 이름에서 첫 숫자가 등장하기 직전까지를 mod 식별 prefix 로 사용.
     */
    private fun extractModFilePrefix(fileName: String): String {
        val nameOnly = fileName.removeSuffix(".jar")
        val m = Regex("^([a-zA-Z][a-zA-Z0-9_\\-]*?)[-_]+\\d").find(nameOnly)
        return m?.groupValues?.get(1) ?: nameOnly
    }

    private fun removeConflictingJars(outDir: File, newFileName: String) {
        val newPrefix = extractModFilePrefix(newFileName)
        if (newPrefix.length < 3) return  // "fa" 같은 짧은 건 위험해서 스킵

        outDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.extension != "jar") return@forEach
            if (f.name == newFileName) return@forEach              // 정확히 같으면 두기
            if (f.name.endsWith(".disabled")) return@forEach       // 비활성은 손대지 않음

            val oldPrefix = extractModFilePrefix(f.name)
            if (oldPrefix.equals(newPrefix, ignoreCase = true)) {
                Log.d("TURTLE_LAUNCHER",
                    "🗑 같은 prefix($newPrefix) 기존 jar 제거: ${f.name}")
                f.delete()
            }
        }
    }

    /** 파일의 gameVersions에서 실제 마인크래프트 버전(예: "1.20.1")만 추출 */
    private fun CurseForgeFile.primaryMcVersion(): String? =
        gameVersions.firstOrNull { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }

    /** 파일의 gameVersions에서 로더명("fabric"/"forge"/"neoforge"/"quilt") 추출 */
    private fun CurseForgeFile.primaryLoader(): String? =
        gameVersions.firstOrNull {
            it.equals("Fabric", true) || it.equals("Forge", true)
                    || it.equals("NeoForge", true) || it.equals("Quilt", true)
        }?.lowercase()

    /**
     * URL → destination 다운로드. 진행률은 _progress 로 publish.
     * DownloadProgress.current/total 이 Int 라서 KB 단위로 환산해 안전 범위 유지.
     */
    private fun downloadFile(url: String, destination: File, displayName: String) {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { response ->
            val body = response.body ?: return
            val contentLen = body.contentLength()
            val totalKb = if (contentLen > 0) (contentLen / 1024).toInt().coerceAtLeast(1) else 0

            // 속도 측정용 상태. 일정 간격(150ms)마다 구간 평균 속도를 갱신해 표시가 출렁이지 않게 한다.
            val startNs = System.nanoTime()
            var lastTickNs = startNs
            var lastTickBytes = 0L
            var speedBps = 0.0
            var lastPublishNs = 0L

            fun publish(downloaded: Long, done: Boolean) {
                // 화면 갱신을 150ms 로 throttling (Compose 리컴포지션 폭주 방지)
                val now = System.nanoTime()
                if (!done && now - lastPublishNs < 150_000_000L) return
                lastPublishNs = now
                _progress.value = DownloadProgress(
                    phase = if (done) DownloadPhase.DONE else DownloadPhase.DOWNLOADING_CLIENT,
                    current = if (totalKb > 0) (downloaded / 1024).toInt().coerceAtMost(totalKb) else 0,
                    total = totalKb,
                    fileName = buildDownloadLabel(displayName, downloaded, contentLen, speedBps),
                )
            }

            publish(0, done = false)

            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        total += read

                        // 구간 속도 갱신
                        val now = System.nanoTime()
                        val dtNs = now - lastTickNs
                        if (dtNs >= 150_000_000L) {           // 0.15s 마다
                            val dBytes = total - lastTickBytes
                            speedBps = dBytes * 1_000_000_000.0 / dtNs
                            lastTickNs = now
                            lastTickBytes = total
                        }
                        publish(total, done = false)
                    }
                    // 전체 평균으로 마지막 속도 보정(짧은 파일은 구간 샘플이 안 잡힐 수 있음)
                    val elapsed = (System.nanoTime() - startNs).coerceAtLeast(1)
                    if (speedBps <= 0.0) speedBps = total * 1_000_000_000.0 / elapsed
                    publish(total, done = false)
                }
            }

            _progress.value = DownloadProgress(
                phase = DownloadPhase.DONE,
                current = totalKb,
                total = totalKb,
                fileName = buildDownloadLabel(displayName, contentLen.coerceAtLeast(0), contentLen, speedBps),
            )
        }
    }

    /**
     * 다운로드 팝업에 보일 한 줄 라벨.
     *   "<파일명> · 12.3 MB / 45.0 MB · 8.4 MB/s"
     * 총 크기를 모르면(Content-Length 없음) 받은 양과 속도만 표시한다.
     */
    private fun buildDownloadLabel(
        name: String,
        downloaded: Long,
        totalBytes: Long,
        speedBps: Double,
    ): String = buildString {
        append(name)
        if (totalBytes > 0) {
            append(" · ${formatBytes(downloaded)} / ${formatBytes(totalBytes)}")
        } else if (downloaded > 0) {
            append(" · ${formatBytes(downloaded)}")
        }
        if (speedBps > 1.0) append(" · ${formatSpeed(speedBps)}")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L         -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L                 -> String.format("%.0f KB", bytes / 1024.0)
        else                           -> "$bytes B"
    }

    private fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec >= 1024.0 * 1024.0 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024.0          -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else                           -> String.format("%.0f B/s", bytesPerSec)
    }

    /**
     * 콘텐츠(모드팩/모드) 로고를 인스턴스 폴더에 내려받아 아이콘으로 저장한다.
     * 성공하면 저장된 파일의 절대경로, 실패(또는 url 없음)하면 null 을 반환한다.
     * 호출부는 이 값을 InstanceMeta.iconPath 로 저장하고, null 이면 기본 아이콘으로 폴백한다.
     */

    /**
     * 같은 이름의 모드팩을 다시 설치(업데이트/재설치)할 때, 이전 설치에서 남은
     * mods/config/libraries/versions/셰이더팩/리소스팩 등을 전부 지우고 새로
     * 시작한다. instanceId 가 모드팩 "이름" 기반이라(InstanceManager.modpackId),
     * 재설치 시 같은 폴더를 그대로 재사용하게 되는데 — 여기 청소를 안 하면
     * 예전 MC 버전용 라이브러리/모드가 새 모드팩 파일과 섞여서 충돌하거나,
     * 심하면 예전 메타(바닐라처럼 동작)로 열리는 문제가 있었다.
     *
     * ⚠️ saves(월드)/screenshots 는 사용자 데이터라 지우지 않고 그대로 둔다 —
     *   "모드팩 업데이트"가 진행 중이던 게임까지 날려버리면 안 되기 때문.
     */
    private fun cleanStaleInstanceFilesForReinstall(instanceDir: File) {
        if (!instanceDir.exists()) return
        val preserve = setOf("saves", "screenshots")
        instanceDir.listFiles()?.forEach { child ->
            if (child.name in preserve) return@forEach
            val deleted = if (child.isDirectory) child.deleteRecursively() else child.delete()
            if (!deleted) {
                Log.w("TURTLE_LAUNCHER", "⚠️ 재설치 정리 중 삭제 실패: ${child.name}")
            }
        }
        Log.d("TURTLE_LAUNCHER", "🧹 재설치 전 이전 파일 정리 완료: ${instanceDir.name} (saves/screenshots 는 보존)")
    }

    /**
     * @param logoUrl  CurseForge/Modrinth 로고 URL. CurseForgeMod.logo?.url / ModrinthProject.iconUrl 등.
     * @param instanceDir 인스턴스 루트(아이콘은 그 안 icon.png 로 저장).
     */
    private fun downloadInstanceIcon(logoUrl: String?, instanceDir: File): String? {
        if (logoUrl.isNullOrBlank()) return null
        return try {
            val iconFile = File(instanceDir, "icon.png").also { it.parentFile?.mkdirs() }
            val req = Request.Builder().url(logoUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("TURTLE_LAUNCHER", "아이콘 다운로드 실패(${resp.code}): $logoUrl")
                    return null
                }
                val body = resp.body ?: return null
                body.byteStream().use { input ->
                    iconFile.outputStream().use { input.copyTo(it) }
                }
            }
            if (iconFile.length() > 0) {
                Log.d("TURTLE_LAUNCHER", "✅ 인스턴스 아이콘 저장: ${iconFile.absolutePath}")
                iconFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.w("TURTLE_LAUNCHER", "아이콘 다운로드 예외: ${e.message}")
            null
        }
    }

    /**
     * 이미 만들어진 인스턴스에 콘텐츠 로고를 받아 아이콘으로 입힌다(iconPath 만 갱신 저장).
     * 새 인스턴스에 모드를 설치할 때, 로더 기본 아이콘 대신 해당 모드의 로고를 쓰기 위함.
     * 메타가 없거나 다운로드 실패하면 조용히 무시(기존 메타 유지).
     * 이미 아이콘이 있으면 덮어쓰지 않는다.
     */
    private fun applyContentIconToInstance(instanceId: String, logoUrl: String?) {
        if (logoUrl.isNullOrBlank()) return
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir) ?: return
        if (meta.iconPath != null) return
        val path = downloadInstanceIcon(logoUrl, instanceDir) ?: return
        InstanceManager.saveMeta(context, meta.copy(iconPath = path))
    }

    // ───── 인스턴스 ID 추적 (설치 여부 표시) ─────

    /**
     * 설치된 모드팩 실행 준비. 성공하면 LaunchParams 를 반환하고, 호출부(ViewModel)가
     * 이걸로 실제 MinecraftActivity.start(activityContext, ...) 를 호출한다 — Activity가
     * 아닌(=ApplicationContext) 곳에서 startActivity 를 직접 하면 FLAG_ACTIVITY_NEW_TASK
     * 없이는 크래시 나기 때문에, 이 repository 는 "실행 준비"까지만 하고 실제 Activity 전환은
     * 호출부에 맡긴다.
     */
    suspend fun launchMod(mod: ContentItem): kr.co.donghyun.turtlelauncher.domain.model.LaunchParams? {
        val session = MicrosoftAuthManager.loadSession(context)
        val isLoggedIn = (session != null && session.refreshToken.isNotEmpty()) ||
            ThirdPartyAuthManager.load(context) != null ||
            LocalAccountManager.load(context) != null

        if (!isLoggedIn) {
            Toast.makeText(context, "로그인 이후에 플레이가 가능합니다.", Toast.LENGTH_SHORT).show()
            return null
        }

        val instanceId = InstanceManager.modpackId(mod.name)
        val instanceDir = InstanceManager.instanceDir(context, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir)
        if (meta == null) {
            Log.e("TURTLE_LAUNCHER", "❌ 인스턴스 메타 없음: $instanceId — 모드팩을 다시 설치하세요")
            _statusMessage.value = "❌ ${mod.name} 인스턴스가 없음 — 다시 설치하세요"
            return null
        }

        Log.d("TURTLE_LAUNCHER",
            "▶ 실행: id=$instanceId mc=${meta.mcVersion} loader=${meta.loaderType ?: "vanilla"} " +
                    "mainClass=${meta.mainClass} extraJars=${meta.extraJars.size}")

        return try {
            withContext(Dispatchers.IO) {
                val internalBase = context.filesDir
                val nativesDir = File(internalBase, "natives")

                // 1) APK 의 .so → filesDir/natives/ 로 복사 (MinecraftActivity 가 여기서 dlopen)
                copyNativesFromApkLibDir(nativesDir)

                // 2) LWJGL 이 native 추출하려는 폴더에 미리 .so 깔아두기 (mc 버전마다)
                prePopulateLwjglExtractDir(nativesDir, meta.mcVersion)
            }
            kr.co.donghyun.turtlelauncher.domain.model.LaunchParams(
                versionId = meta.mcVersion,
                assetIndexId = meta.assetIndexId,
                extraJars = meta.extraJars,
                mainClass = meta.mainClass,
                instanceDirPath = instanceDir.absolutePath,
            )
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "▶ 실행 준비 실패: ${e.message}", e)
            _statusMessage.value = "❌ 실행 실패: ${e.message}"
            null
        }
    }

    /**
     * MainActivity 와 동일한 동작 — APK 의 native .so 들을 filesDir/natives/ 로 미러링.
     * arm64-v8a 만 추출. nativeLibraryDir 에 없는 것들 (예: assets 안에 들어간 .so) 까지
     * APK zip 직접 열어서 보강.
     */
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

    /**
     * MainActivity 와 동일 — LWJGL 이 native 자동 추출하려는 후보 폴더들에 미리 .so 배치.
     * 안드로이드는 jar 안 native 추출이 막혀있어서 안 하면 UnsatisfiedLinkError.
     */
    private fun prePopulateLwjglExtractDir(nativesDir: File, versionId: String) {
        listOf("3.2.1", "3.2.2", "3.2.1-build-12", "3.2.2-build-12", "3.3.3", "3.3.3-snapshot").forEach { version ->
            val lwjglDir = File(context.getExternalFilesDir(null), "mc_$versionId/.lwjgl/$version")
            if (lwjglDir.exists()) lwjglDir.deleteRecursively()
            lwjglDir.mkdirs()
            nativesDir.listFiles()?.forEach { soFile ->
                soFile.copyTo(File(lwjglDir, soFile.name), overwrite = true)
                File(lwjglDir, soFile.name).setExecutable(true, false)
            }
        }
    }

    /**
     * 설치(다운로드) 시작. 이미 다른 설치가 진행 중이면 거부하고 false 를 반환한다.
     * (동시 다운로드 방지 — 모든 설치 경로가 이 함수를 단일 관문으로 통과한다)
     */
    private fun beginInstall(mod: ContentItem, message: String): Boolean {
        if (_isInstalling.value) {
            val current = _installingModId.value
            Toast.makeText(
                context,
                if (current == mod.trackKey) "이미 설치 중입니다."
                else "다른 항목을 설치하는 중입니다. 완료된 후 다시 시도해주세요.",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        _isInstalling.value = true
        _installingModId.value = mod.trackKey
        _statusMessage.value = message
        _progress.value = DownloadProgress()
        return true
    }

    private fun endInstall() {
        _isInstalling.value = false
        _installingModId.value = null
        _statusMessage.value = ""
        _progress.value = DownloadProgress()
    }

    fun isLegacyVersion(versionId: String): Boolean {
        // 1.12.2 이하: legacy (AWT 필요)
        // 1.13+: modern (LWJGL3, AWT 불필요)
        // 베타/알파/클래식/인데브(b1.x, a1.x, a0.x, c0.x, inf-, rd-) 등 "1."로 시작하지
        // 않는 구버전 표기는 기존 파서가 못 잡아 modern 으로 오판했었다 (예: b1.7.3 즉시 크래시).
        val id = versionId.trim().lowercase()
        if (id.startsWith("b1.") || id.startsWith("a1.") || id.startsWith("a0.") ||
            id.startsWith("c0.") || id.startsWith("inf-") || id.startsWith("rd-")) {
            return true
        }
        val major = Regex("""^1\.(\d+)""").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return major <= 12
    }

    private fun resolveDownloadUrl(file: CurseForgeFile): String {
        val direct = file.downloadUrl
        if (!direct.isNullOrBlank()) return direct

        val part1 = file.id / 1000
        val part2 = file.id % 1000
        val safeName = file.fileName.replace(" ", "%20")
        return "https://edge.forgecdn.net/files/$part1/$part2/$safeName".also {
            Log.i("TURTLE_LAUNCHER",
                "🔁 downloadUrl=null → CDN fallback: mod=${file.id} → $it")
        }
    }

}
