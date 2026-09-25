package kr.co.donghyun.turtlelauncher.presentation.util.mods

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeManifest
import kr.co.donghyun.turtlelauncher.data.mods.ManifestFile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.zip.ZipInputStream

/**
 * "인스턴스 설정 → 모드팩 가져오기" 로 로컬 zip 파일을 기존 인스턴스에 병합한다.
 *
 * ⚠️ 표준 CurseForge 모드팩 export 는 대부분의 모드를 zip 안에 직접 담지 않고
 * manifest.json 의 files[]({projectID, fileID}) 로만 "참조"한다(용량을 줄이고,
 * 배포권 문제를 피하기 위해 — CurseForge 자체 설치 프로그램이 그 배열을 보고
 * CurseForge API 에서 각 파일을 따로 받아오는 방식). zip 안에 실제로 박혀있는
 * mods 폴더 안의 jar 파일은 보통 저작권상 재배포 불가능한 소수의 모드(유료 애드온 등)뿐이다.
 *
 * 예전 버전은 zip 안에 "직접 들어있는" 파일만 추출했어서, files[] 로 참조된
 * 나머지 대부분의 모드가 통째로 빠진 채 "가져오기 완료"로 처리되고 있었다 —
 * 그 결과 게임을 실행하면 의존성이 무더기로 빠져서 크래시가 났다. 이제
 * ModPackInstaller(콘텐츠 브라우저 설치 경로)와 동일한 방식으로 CurseForge API 를
 * 통해 files[] 를 마저 해석해서 받아온다.
 */
object ModpackImporter {

    data class Manifest(
        val name: String?,
        val mcVersion: String?,
        val loaderType: String?,
        val loaderVersion: String?,
        val modCount: Int,
    )

    sealed interface Result {
        data class Success(
            val modCount: Int,
            val configCount: Int,
            val manifest: Manifest?,
            val mcMismatch: Boolean,
            val failedDownloads: Int = 0,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    private const val CLASS_ID_RESOURCE_PACK = 12
    private const val CLASS_ID_SHADER_PACK = 6552
    private const val PARALLELISM = 48

    private val client by lazy { OkHttpClient() }
    private val gson = Gson()

    /**
     * @param zipUri            SAF 로 고른 모드팩 zip
     * @param gameDir           대상 인스턴스의 게임 디렉터리(여기 아래 mods/, config/ 로 풀린다)
     * @param currentMcVersion  현재 인스턴스 mc 버전(호환 경고용, 없으면 null)
     * @param currentLoaderType 현재 인스턴스 로더("fabric"/"forge"/"neoforge"/null=바닐라). 로더 게이트용.
     * @param onProgress        진행 표시용(선택). files[] 다운로드가 오래 걸릴 수 있어 넣어둠.
     */
    suspend fun import(
        context: Context,
        zipUri: Uri,
        gameDir: File,
        currentMcVersion: String?,
        currentLoaderType: String?,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        try {
            // 로컬 파일 SAF Uri 는 여러 번 열어야 하므로(1차: manifest, 2차: 추출) 임시로 복사해둔다.
            val tempZip = File.createTempFile("modpack_import_", ".zip", context.cacheDir)
            try {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    tempZip.outputStream().use { input.copyTo(it) }
                } ?: return@withContext Result.Failure("파일을 열 수 없습니다.")

                val cfManifest = readCurseForgeManifest(tempZip)
                val manifest = cfManifest?.let {
                    val loaderEntry = it.minecraft.modLoaders.firstOrNull { l -> l.primary } ?: it.minecraft.modLoaders.firstOrNull()
                    val (loaderType, loaderVersion) = parseLoaderId(loaderEntry?.id ?: "")
                    Manifest(
                        name = it.name.ifBlank { null },
                        mcVersion = it.minecraft.version.ifBlank { null },
                        loaderType = loaderType,
                        loaderVersion = loaderVersion,
                        modCount = it.files.size,
                    )
                }

                val packLoader = manifest?.loaderType?.trim()?.lowercase()?.ifBlank { null }
                val curLoader = currentLoaderType?.trim()?.lowercase()?.ifBlank { null }

                // 모드팩 로더를 알 수 있고(known) 현재 로더와 다르면 거부.
                // (현재 로더 null = 바닐라 → 모든 모드 로더와 "다름")
                if (packLoader != null && packLoader != curLoader) {
                    return@withContext Result.Failure(
                        "모드 로더가 달라 가져올 수 없어요. " +
                                "(모드팩: ${loaderDisplay(packLoader)} · 현재 인스턴스: ${loaderDisplay(curLoader)})"
                    )
                }

                val overridesFolder = cfManifest?.overrides?.trim()?.trim('/')?.ifBlank { null } ?: "overrides"
                val overridesPrefix = "$overridesFolder/"

                val modsDir = File(gameDir, "mods")
                val configDir = File(gameDir, "config")
                val resourcePacksDir = File(gameDir, "resourcepacks")
                val shaderPacksDir = File(gameDir, "shaderpacks")
                val gameCanonical = gameDir.canonicalPath

                // ── 1) zip 안에 직접 들어있는 파일(overrides/ 아래) 추출 ──
                var modCount = 0
                var configCount = 0
                val embeddedModFileNames = mutableSetOf<String>()

                ZipInputStream(BufferedInputStream(tempZip.inputStream())).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val rawName = entry.name.replace('\\', '/')
                        val name = rawName.removePrefix(overridesPrefix)
                        if (!entry.isDirectory) {
                            when {
                                name.startsWith("mods/") && name.endsWith(".jar", ignoreCase = true) -> {
                                    val fileName = name.substringAfterLast('/')
                                    if (fileName.isNotBlank()) {
                                        val target = File(modsDir, fileName)
                                        if (isWithin(target, gameCanonical)) {
                                            target.parentFile?.mkdirs()
                                            target.outputStream().use { zin.copyTo(it) }
                                            modCount++
                                            embeddedModFileNames += fileName.lowercase()
                                        }
                                    }
                                }

                                name.startsWith("config/") -> {
                                    val rel = name.removePrefix("config/")
                                    if (rel.isNotBlank() && !rel.contains("..")) {
                                        val target = File(configDir, rel)
                                        if (isWithin(target, gameCanonical)) {
                                            target.parentFile?.mkdirs()
                                            target.outputStream().use { zin.copyTo(it) }
                                            configCount++
                                        }
                                    }
                                }

                                else -> { /* manifest.json 등 그 외 항목 무시 */ }
                            }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }

                // ── 2) manifest.files[] 로만 참조된(zip 안에 실제로는 없는) 모드를
                //   CurseForge API 로 찾아서 받는다 — 표준 export 의 대부분은 여기 해당. ──
                var failedDownloads = 0
                val requiredFiles = cfManifest?.files?.filter { it.required } ?: emptyList()
                if (requiredFiles.isNotEmpty()) {
                    val curseForgeApi = CurseForgeAPI()
                    val fileIds = requiredFiles.map { it.fileID }
                    val fileInfoMap = try {
                        curseForgeApi.getFiles(fileIds).associateBy { it.id }
                    } catch (e: Exception) {
                        Log.w("TURTLE_LAUNCHER", "CurseForge getFiles 실패: ${e.message}")
                        emptyMap()
                    }
                    // zip 안에 이미 직접 들어있던 파일과 이름이 겹치면 중복 다운로드하지 않는다
                    // (드물지만, 일부 모드팩은 재배포권 있는 파일을 overrides 에도 같이 담는 경우가 있음).
                    val toDownload = requiredFiles.filter { mf ->
                        val fname = fileInfoMap[mf.fileID]?.fileName?.lowercase()
                        fname == null || fname !in embeddedModFileNames
                    }

                    val projectIds = toDownload.map { it.projectID }.distinct()
                    val classIdByProject = try {
                        curseForgeApi.getMods(projectIds).associate { it.id to it.classId }
                    } catch (e: Exception) {
                        Log.w("TURTLE_LAUNCHER", "CurseForge getMods 실패: ${e.message}")
                        emptyMap()
                    }

                    suspend fun downloadOne(mf: ManifestFile): Boolean = try {
                        val fileInfo = fileInfoMap[mf.fileID]
                        val url = fileInfo?.downloadUrl ?: curseForgeApi.getFileDownloadUrl(mf.projectID, mf.fileID)
                        if (url == null) {
                            false
                        } else {
                            val fileName = fileInfo?.fileName ?: "mod_${mf.fileID}.jar"
                            val destDir = when (classIdByProject[mf.projectID]) {
                                CLASS_ID_RESOURCE_PACK -> resourcePacksDir
                                CLASS_ID_SHADER_PACK -> shaderPacksDir
                                else -> modsDir
                            }
                            destDir.mkdirs()
                            val destFile = File(destDir, fileName)
                            if (!destFile.exists() || destFile.length() == 0L) {
                                downloadFile(url, destFile)
                            }
                            if (destDir == modsDir) modCount++
                            true
                        }
                    } catch (e: Exception) {
                        Log.w("TURTLE_LAUNCHER", "모드 다운로드 실패: ${mf.fileID} (${e.message})")
                        false
                    }

                    val firstPassFailures = Collections.synchronizedList(mutableListOf<ManifestFile>())
                    var completed = 0
                    coroutineScope {
                        val semaphore = Semaphore(PARALLELISM)
                        toDownload.map { mf ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    if (!downloadOne(mf)) firstPassFailures.add(mf)
                                    completed++
                                    onProgress?.invoke(completed, toDownload.size)
                                }
                            }
                        }.awaitAll()
                    }

                    if (firstPassFailures.isNotEmpty()) {
                        Log.w("TURTLE_LAUNCHER", "가져오기 1차 실패 ${firstPassFailures.size}개 — 배치 재시도")
                        val stillFailed = Collections.synchronizedList(mutableListOf<ManifestFile>())
                        coroutineScope {
                            val semaphore = Semaphore(PARALLELISM)
                            firstPassFailures.toList().map { mf ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        if (!downloadOne(mf)) stillFailed.add(mf)
                                    }
                                }
                            }.awaitAll()
                        }
                        failedDownloads = stillFailed.size
                    }
                }

                if (modCount == 0 && configCount == 0) {
                    return@withContext Result.Failure("이 zip 에서 가져올 모드/설정을 찾지 못했어요. 올바른 모드팩 zip 인가요?")
                }

                val packMc = manifest?.mcVersion
                val mismatch = !packMc.isNullOrBlank() &&
                        !currentMcVersion.isNullOrBlank() &&
                        packMc != currentMcVersion

                Result.Success(modCount, configCount, manifest, mismatch, failedDownloads)
            } finally {
                tempZip.delete()
            }
        } catch (e: Exception) {
            Result.Failure("가져오기 실패: ${e.message}")
        }
    }

    /** manifest.json 을 CurseForgeManifest(정식 스키마)로 파싱. 없거나 형식이 다르면 null. */
    private fun readCurseForgeManifest(zipFile: File): CurseForgeManifest? {
        return try {
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val n = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && n == "manifest.json") {
                        val json = zin.readBytes().toString(Charsets.UTF_8)
                        return gson.fromJson(json, CurseForgeManifest::class.java)
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "manifest.json 파싱 실패: ${e.message}")
            null
        }
    }

    private fun parseLoaderId(id: String): Pair<String?, String?> = when {
        id.startsWith("fabric-") -> "fabric" to id.removePrefix("fabric-")
        id.startsWith("quilt-") -> "quilt" to id.removePrefix("quilt-")
        id.startsWith("forge-") -> "forge" to id.removePrefix("forge-")
        id.startsWith("neoforge-") -> "neoforge" to id.removePrefix("neoforge-")
        else -> null to null
    }

    private fun loaderDisplay(loader: String?): String = when (loader) {
        "fabric"   -> "Fabric"
        "forge"    -> "Forge"
        "neoforge" -> "NeoForge"
        null       -> "없음(바닐라)"
        else       -> loader
    }

    /** target 의 정규 경로가 base 안에 있는지(경로 탈출 방지) */
    private fun isWithin(target: File, baseCanonical: String): Boolean {
        return try {
            val tp = target.canonicalPath
            tp == baseCanonical || tp.startsWith(baseCanonical + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("다운로드 실패: $url (${response.code})")
            val body = response.body ?: throw Exception("응답 본문 없음: $url")
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { input.copyTo(it) }
            }
        }
    }
}
