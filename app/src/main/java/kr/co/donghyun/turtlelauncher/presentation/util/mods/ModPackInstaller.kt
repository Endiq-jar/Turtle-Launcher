package kr.co.donghyun.turtlelauncher.presentation.util.mods

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.co.donghyun.turtlelauncher.data.instance.InstanceManager
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeManifest
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeMod
import kr.co.donghyun.turtlelauncher.data.mods.ManifestFile
import kr.co.donghyun.turtlelauncher.data.mojang.DownloadPhase
import kr.co.donghyun.turtlelauncher.data.mojang.DownloadProgress
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

data class ModPackInstallResult(
    val success: Boolean,
    val mcVersion: String = "",
    val loaderType: String? = null,      // "fabric", "forge", "neoforge"
    val loaderVersion: String? = null,
    val gameDir: File? = null,
    val error: String? = null
)

/**
 * ⚠️ 다운로드 방식은 ZL2(ZalithLauncher2) 의 ModDownloader 를 참고해서 개선함:
 *   - 동시성 48개(기존 순차 대비 대폭 상향)
 *   - 배치 재시도: 1차로 전체를 병렬로 돌리고, 실패한 것만 모아뒀다가 전체가 끝난
 *     뒤에 그 실패분만 다시 한 번 통째로 재시도. 그래도 실패하면 명확한 예외로
 *     설치 전체를 실패 처리(무엇이 빠졌는지 조용히 넘어가지 않음).
 */
class ModPackInstaller(
    private val baseDir: File,
    private val curseForgeApi: CurseForgeAPI,
    private val onProgress: (DownloadProgress) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 48
        })
        .build()
    private val gson = Gson()
    private val PARALLELISM = 48

    companion object {
        // CurseForge(gameId=432, Minecraft) 프로젝트 classId. 공식 문서에 명시된 고정값.
        private const val CLASS_ID_RESOURCE_PACK = 12
        private const val CLASS_ID_SHADER_PACK = 6552
    }

    // 알려진 비호환 모드 목록
    private val INCOMPATIBLE_MODS = listOf<String>()

    suspend fun install(mod: CurseForgeMod, fileId: Int, mcVersionOverride: String? = null ): ModPackInstallResult {
        val gameDir = baseDir

        // 캐시 — InstanceMeta 그대로 사용
        val existingMeta = InstanceManager.loadMeta(gameDir)
        if (existingMeta != null && existingMeta.loaderType != null) {
            Log.d("TURTLE_LAUNCHER", "✅ 메타 캐시 발견: ${mod.name}")
            return ModPackInstallResult(
                success = true,
                mcVersion = existingMeta.mcVersion,
                loaderType = existingMeta.loaderType,
                loaderVersion = existingMeta.loaderVersion,
                gameDir = gameDir
            )
        }

        onProgress(DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST, fileName = mod.name))

        // 1. 모드팩 zip 다운로드
        val downloadUrl = curseForgeApi.getFileDownloadUrl(mod.id, fileId)
            ?: return ModPackInstallResult(success = false, error = "다운로드 URL을 가져올 수 없음")

        val modpackZip = File(baseDir, "temp/modpack_${mod.id}_${fileId}.zip")
        modpackZip.parentFile?.mkdirs()

        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_CLIENT, fileName = "${mod.name}.zip"))
        downloadFile(downloadUrl, modpackZip) { downloaded, total, speed ->
            onProgress(DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_CLIENT,
                fileName = "${mod.name}.zip · ${byteDetail(downloaded, total, speed)}",
            ))
        }

        val manifest = extractManifest(modpackZip)
            ?: return ModPackInstallResult(success = false, error = "manifest.json 없음")

        val manifestMc = manifest.minecraft.version
        val isManifestMcValid = manifestMc.matches(Regex("^1\\.\\d+(\\..*)?$"))
                || manifestMc.matches(Regex("^\\d+w\\d+[a-z]$"))

        val mcVersion = when {
            isManifestMcValid -> manifestMc
            mcVersionOverride != null -> {
                Log.w("TURTLE_LAUNCHER", "manifest의 mcVersion='$manifestMc' 비정상 → CurseForge 메타의 '$mcVersionOverride' 사용")
                mcVersionOverride
            }
            else -> return ModPackInstallResult(
                success = false,
                error = "MC 버전 확인 실패 (manifest='$manifestMc', file 메타도 없음)"
            )
        }

        Log.d("TURTLE_LAUNCHER", "📦 ${manifest.name} v${manifest.version}, MC=$mcVersion, loader=${manifest.minecraft.modLoaders.firstOrNull { it.primary }?.id}")

        val loaderEntry = manifest.minecraft.modLoaders.firstOrNull { it.primary }
        val (loaderType, loaderVersion) = parseLoaderId(loaderEntry?.id ?: "")

        // overrides 추출
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_LIBRARIES, fileName = "파일 추출 중..."))
        extractOverrides(modpackZip, gameDir, manifest.overrides)

        // 모드 다운로드
        val totalMods = manifest.files.filter { it.required }.size
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        val resourcePacksDir = File(gameDir, "resourcepacks")
        val shaderPacksDir = File(gameDir, "shaderpacks")

        val requiredFiles = manifest.files.filter { it.required }
        val fileIds = requiredFiles.map { it.fileID }
        val fileInfoMap = try {
            curseForgeApi.getFiles(fileIds).associateBy { it.id }
        } catch (_: Exception) { emptyMap() }

        // ★ manifest.files[] 는 {projectID, fileID} 뿐이라 모드/리소스팩/셰이더팩 구분이 없다.
        //   프로젝트(=mod 엔티티)의 classId 를 배치로 조회해서 올바른 폴더에 풀어야 한다.
        //   (이게 없으면 리소스팩/셰이더팩도 전부 mods/ 에 들어가 게임이 인식 못함)
        val projectIds = requiredFiles.map { it.projectID }.distinct()
        val classIdByProject = try {
            curseForgeApi.getMods(projectIds).associate { it.id to it.classId }
        } catch (_: Exception) { emptyMap() }

        suspend fun downloadOneMod(manifestFile: ManifestFile): Boolean {
            return try {
                val fileInfo = fileInfoMap[manifestFile.fileID]
                val modUrl = fileInfo?.downloadUrl
                    ?: curseForgeApi.getFileDownloadUrl(manifestFile.projectID, manifestFile.fileID)
                    ?: return false
                val fileName = fileInfo?.fileName ?: "mod_${manifestFile.fileID}.jar"

                val destDir = when (classIdByProject[manifestFile.projectID]) {
                    CLASS_ID_RESOURCE_PACK -> resourcePacksDir
                    CLASS_ID_SHADER_PACK -> shaderPacksDir
                    else -> modsDir       // 6=Mods 및 미상 항목은 기존처럼 mods/ (안전한 기본값)
                }
                destDir.mkdirs()
                val destFile = File(destDir, fileName)
                if (!destFile.exists() || destFile.length() == 0L) downloadFile(modUrl, destFile)
                true
            } catch (e: Exception) {
                Log.w("TURTLE_LAUNCHER", "모드 다운로드 실패: ${manifestFile.fileID} (${e.message})")
                false
            }
        }

        val completedMods = AtomicInteger(0)
        val firstPassFailures = Collections.synchronizedList(
            mutableListOf<ManifestFile>()
        )
        coroutineScope {
            val semaphore = Semaphore(PARALLELISM)
            requiredFiles.map { manifestFile ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val ok = downloadOneMod(manifestFile)
                        if (!ok) firstPassFailures.add(manifestFile)
                        val done = completedMods.incrementAndGet()
                        onProgress(DownloadProgress(
                            phase = DownloadPhase.DOWNLOADING_ASSETS,
                            current = done, total = totalMods,
                            fileName = "모드 다운로드 중..."
                        ))
                    }
                }
            }.awaitAll()
        }

        if (firstPassFailures.isNotEmpty()) {
            Log.w("TURTLE_LAUNCHER", "모드팩 1차 실패 ${firstPassFailures.size}개 — 배치 재시도")
            val stillFailed = Collections.synchronizedList(mutableListOf<Int>())
            val retryDone = AtomicInteger(0)
            coroutineScope {
                val semaphore = Semaphore(PARALLELISM)
                firstPassFailures.map { manifestFile ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val ok = downloadOneMod(manifestFile)
                            if (!ok) stillFailed.add(manifestFile.fileID)
                            val done = retryDone.incrementAndGet()
                            onProgress(DownloadProgress(
                                phase = DownloadPhase.DOWNLOADING_ASSETS,
                                current = done, total = firstPassFailures.size,
                                fileName = "모드 재시도 중..."
                            ))
                        }
                    }
                }.awaitAll()
            }
            if (stillFailed.isNotEmpty()) {
                modpackZip.delete()
                return ModPackInstallResult(
                    success = false,
                    error = "${stillFailed.size}개 모드 다운로드 실패(재시도 후에도): ${stillFailed.take(10)}" +
                        if (stillFailed.size > 10) " 외 ${stillFailed.size - 10}개" else ""
                )
            }
        }

        modpackZip.delete()
        disableIncompatibleMods(modsDir)

        Log.d("TURTLE_LAUNCHER", "✅ 모드팩 설치: ${mod.name}, MC $mcVersion, $loaderType $loaderVersion")

        return ModPackInstallResult(
            success = true,
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            gameDir = gameDir
        )
    }

    private fun parseLoaderId(id: String): Pair<String?, String?> = when {
        id.startsWith("fabric-") -> "fabric" to id.removePrefix("fabric-")
        id.startsWith("quilt-") -> "quilt" to id.removePrefix("quilt-")
        id.startsWith("forge-") -> "forge" to id.removePrefix("forge-")
        id.startsWith("neoforge-") -> "neoforge" to id.removePrefix("neoforge-")
        else -> null to null
    }

    private fun disableIncompatibleMods(modsDir: File) {
        if (!modsDir.exists()) return
        modsDir.listFiles()?.forEach { file ->
            if (file.extension == "jar") {
                val fileName = file.name.lowercase()
                val isIncompatible = INCOMPATIBLE_MODS.any { mod ->
                    fileName.contains(mod)
                }
                if (isIncompatible) {
                    val disabled = File(file.parent, "${file.name}.disabled")
                    file.renameTo(disabled)
                    Log.d("TURTLE_LAUNCHER", "⚠ 비호환 모드 비활성화: ${file.name}")
                }
            }
        }
    }

    private fun extractManifest(zipFile: File): CurseForgeManifest? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("manifest.json")
                if (entry == null) {
                    // .mrpack (Modrinth) 형식인지 확인
                    val mrpackEntry = zip.getEntry("modrinth.index.json")
                    if (mrpackEntry != null) {
                        Log.e("TURTLE_LAUNCHER", "이 모드팩은 Modrinth(.mrpack) 형식 — 현재 미지원")
                        val mrJson = zip.getInputStream(mrpackEntry).bufferedReader().readText()
                        Log.d("TURTLE_LAUNCHER", "mrpack 내용:\n$mrJson")
                        return null
                    }
                    Log.e("TURTLE_LAUNCHER", "manifest.json도 modrinth.index.json도 없음")
                    Log.d("TURTLE_LAUNCHER", "zip 내부 파일들:")
                    zip.entries().asSequence().take(20).forEach { Log.d("TURTLE_LAUNCHER", "  ${it.name}") }
                    return null
                }
                val json = zip.getInputStream(entry).bufferedReader().readText()
                Log.d("TURTLE_LAUNCHER", "═══ manifest.json 원본 ═══")
                json.lineSequence().take(40).forEach { Log.d("TURTLE_LAUNCHER", it) }
                Log.d("TURTLE_LAUNCHER", "═══════════════════════════")
                gson.fromJson(json, CurseForgeManifest::class.java)
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "manifest.json 파싱 실패: ${e.message}")
            null
        }
    }

    private fun extractOverrides(zipFile: File, gameDir: File, overridesFolder: String) {
        try {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("$overridesFolder/") && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix("$overridesFolder/")
                        val destFile = File(gameDir, relativePath)
                        destFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "overrides 추출 실패: ${e.message}")
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        onBytes: ((downloaded: Long, total: Long, speedBps: Double) -> Unit)? = null,
    ) {
        if (destFile.exists() && destFile.length() > 0) return
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("다운로드 실패: $url (${response.code})")
            val body = response.body ?: throw Exception("응답 본문 없음: $url")
            val contentLen = body.contentLength()

            if (onBytes == null) {
                // 진행 보고가 필요 없는 경우(개별 모드 등) — 기존처럼 통째 복사.
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { input.copyTo(it) }
                }
                return
            }

            // 진행 보고가 필요한 경우(모드팩 zip 등) — 스트리밍하며 속도 계산.
            val startNs = System.nanoTime()
            var lastTickNs = startNs
            var lastTickBytes = 0L
            var speedBps = 0.0
            var lastPublishNs = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        total += read

                        val now = System.nanoTime()
                        val dtNs = now - lastTickNs
                        if (dtNs >= 150_000_000L) {
                            speedBps = (total - lastTickBytes) * 1_000_000_000.0 / dtNs
                            lastTickNs = now
                            lastTickBytes = total
                        }
                        if (now - lastPublishNs >= 150_000_000L) {
                            lastPublishNs = now
                            onBytes(total, contentLen, speedBps)
                        }
                    }
                    val elapsed = (System.nanoTime() - startNs).coerceAtLeast(1)
                    if (speedBps <= 0.0) speedBps = total * 1_000_000_000.0 / elapsed
                    onBytes(total, contentLen, speedBps)
                }
            }
        }
    }

    /** 바이트/속도 → "12.3 MB / 45.0 MB · 8.4 MB/s" 라벨(파일명 뒤에 붙일 상세). */
    private fun byteDetail(downloaded: Long, total: Long, speedBps: Double): String = buildString {
        if (total > 0) append("${fmtBytes(downloaded)} / ${fmtBytes(total)}")
        else if (downloaded > 0) append(fmtBytes(downloaded))
        if (speedBps > 1.0) {
            if (isNotEmpty()) append(" · ")
            append(fmtSpeed(speedBps))
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1024L*1024L*1024L -> String.format("%.2f GB", b / (1024.0*1024.0*1024.0))
        b >= 1024L*1024L       -> String.format("%.1f MB", b / (1024.0*1024.0))
        b >= 1024L             -> String.format("%.0f KB", b / 1024.0)
        else                   -> "$b B"
    }

    private fun fmtSpeed(bps: Double): String = when {
        bps >= 1024.0*1024.0 -> String.format("%.1f MB/s", bps / (1024.0*1024.0))
        bps >= 1024.0        -> String.format("%.0f KB/s", bps / 1024.0)
        else                 -> String.format("%.0f B/s", bps)
    }
}