package com.endiq.turtlelauncher.presentation.util.mods

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.data.mods.MrpackFile
import com.endiq.turtlelauncher.data.mods.MrpackIndex
import com.endiq.turtlelauncher.data.mojang.DownloadPhase
import com.endiq.turtlelauncher.data.mojang.DownloadProgress
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.text.get

/**
 * Modrinth .mrpack 모드팩 설치기.
 *
 * CurseForge 의 ModPackInstaller 와 대칭. 같은 ModPackInstallResult 를 반환해
 * 기존 설치 후처리(인스턴스 생성/로더 설치)를 그대로 재활용한다.
 *
 * .mrpack 구조:
 *  - zip 컨테이너, 내부 modrinth.index.json 이 매니페스트
 *  - files[]: 각 항목이 직접 downloads[] URL + path(게임 디렉터리 상대경로) 보유
 *             → CurseForge 처럼 파일 메타를 또 조회할 필요가 없다(다운로드가 직접적).
 *  - overrides/, client-overrides/ : 게임 디렉터리에 그대로 덮어쓸 파일들
 *  - dependencies: {"minecraft":"1.20.1","fabric-loader":"0.15.7"} 같은 맵
 *
 * ⚠️ 다운로드 방식은 ZL2(ZalithLauncher2) 의 ModDownloader 를 참고해서 개선함:
 *   - 동시성 48개(기존 순차 대비 대폭 상향)
 *   - files[].downloads 는 원래 미러 목록(여러 URL)인데 기존엔 firstOrNull() 로 첫 번째만
 *     썼음 — 이제 실패하면 다음 미러로 순서대로 시도한다.
 *   - 배치 재시도: 1차로 전체를 병렬로 돌리고, 실패한 것만 모아뒀다가 전체가 끝난
 *     뒤에 그 실패분만 다시 한 번 통째로 재시도. 그래도 실패하면 명확한 예외로
 *     설치 전체를 실패 처리(무엇이 빠졌는지 조용히 넘어가지 않음).
 */
class MrpackInstaller(
    private val baseDir: File,
    private val modrinthApi: ModrinthAPI,
    private val onProgress: (DownloadProgress) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 48
        })
        .build()
    private val gson = Gson()
    private val PARALLELISM = 48

    /**
     * @param mrpackUrl    설치할 .mrpack 파일 URL (ModrinthVersion.files 의 primary url)
     * @param displayName  진행 표시용 이름
     */
    suspend fun install(mrpackUrl: String, displayName: String): ModPackInstallResult {
        val gameDir = baseDir

        // 캐시 — 기존 인스턴스 메타 있으면 재사용 (CurseForge 쪽과 동일 동작)
        val existingMeta = InstanceManager.loadMeta(gameDir)
        if (existingMeta != null && existingMeta.loaderType != null) {
            Log.d("TURTLE_LAUNCHER", "✅ 메타 캐시 발견(.mrpack): $displayName")
            return ModPackInstallResult(
                success = true,
                mcVersion = existingMeta.mcVersion,
                loaderType = existingMeta.loaderType,
                loaderVersion = existingMeta.loaderVersion,
                gameDir = gameDir
            )
        }

        onProgress(DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST, fileName = displayName))

        // 1) .mrpack 다운로드
        val mrpackZip = File(baseDir, "temp/mrpack_${System.currentTimeMillis()}.mrpack")
        mrpackZip.parentFile?.mkdirs()
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_CLIENT, fileName = "$displayName.mrpack"))
        try {
            downloadFile(mrpackUrl, mrpackZip) { downloaded, total, speed ->
                onProgress(DownloadProgress(
                    phase = DownloadPhase.DOWNLOADING_CLIENT,
                    fileName = "$displayName.mrpack · ${byteDetail(downloaded, total, speed)}",
                ))
            }
        } catch (e: Exception) {
            return ModPackInstallResult(success = false, error = ".mrpack 다운로드 실패: ${e.message}")
        }

        // 2) modrinth.index.json 파싱
        val index = extractIndex(mrpackZip)
            ?: return ModPackInstallResult(success = false, error = "modrinth.index.json 없음/파싱 실패").also { mrpackZip.delete() }

        val mcVersion = index.dependencies["minecraft"]
            ?: return ModPackInstallResult(success = false, error = "mrpack에 minecraft 버전 없음").also { mrpackZip.delete() }

        val (loaderType, loaderVersion) = resolveLoader(index.dependencies)

        Log.d("TURTLE_LAUNCHER", "📦 .mrpack ${index.name} v${index.versionId}, MC=$mcVersion, loader=$loaderType $loaderVersion")

        // 3) overrides 추출 (overrides/, client-overrides/ 둘 다)
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_LIBRARIES, fileName = "파일 추출 중..."))
        extractOverrides(mrpackZip, gameDir, "overrides")
        extractOverrides(mrpackZip, gameDir, "client-overrides")

        // 4) files[] 다운로드 — 병렬(48개 동시) + 항목당 미러(downloads[] 전체) 순서대로 시도
        //   + 배치 재시도(1차 완료 후 실패분만 모아 한 번 더 통째로 재시도).
        val targetFiles = index.files.filter { isClientWanted(it) }
        val total = targetFiles.size
        val completed = AtomicInteger(0)
        val firstPassFailures = Collections.synchronizedList(mutableListOf<MrpackFile>())

        suspend fun downloadOneWithMirrors(f: MrpackFile): Boolean {
            val dest = File(gameDir, f.path)
            dest.parentFile?.mkdirs()
            if (dest.exists() && dest.length() > 0) return true
            for (url in f.downloads) {
                try {
                    downloadFile(url, dest)
                    if (dest.exists() && dest.length() > 0) return true
                } catch (e: Exception) {
                    Log.w("TURTLE_LAUNCHER", "mrpack 미러 실패, 다음 시도: ${f.path} ($url) - ${e.message}")
                }
            }
            return false
        }

        coroutineScope {
            val semaphore = Semaphore(PARALLELISM)
            targetFiles.map { f ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val ok = downloadOneWithMirrors(f)
                        if (!ok) firstPassFailures.add(f)
                        val done = completed.incrementAndGet()
                        onProgress(DownloadProgress(
                            phase = DownloadPhase.DOWNLOADING_ASSETS,
                            current = done, total = total,
                            fileName = f.path.substringAfterLast('/')
                        ))
                    }
                }
            }.awaitAll()
        }

        if (firstPassFailures.isNotEmpty()) {
            Log.w("TURTLE_LAUNCHER", "mrpack 1차 실패 ${firstPassFailures.size}개 — 배치 재시도")
            val stillFailed = Collections.synchronizedList(mutableListOf<String>())
            val retryDone = AtomicInteger(0)
            coroutineScope {
                val semaphore = Semaphore(PARALLELISM)
                firstPassFailures.map { f ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val ok = downloadOneWithMirrors(f)
                            if (!ok) stillFailed.add(f.path)
                            val done = retryDone.incrementAndGet()
                            onProgress(DownloadProgress(
                                phase = DownloadPhase.DOWNLOADING_ASSETS,
                                current = done, total = firstPassFailures.size,
                                fileName = "재시도: ${f.path.substringAfterLast('/')}"
                            ))
                        }
                    }
                }.awaitAll()
            }
            if (stillFailed.isNotEmpty()) {
                mrpackZip.delete()
                return ModPackInstallResult(
                    success = false,
                    error = "${stillFailed.size}개 파일 다운로드 실패(재시도 후에도):\n" +
                        stillFailed.take(10).joinToString("\n") +
                        if (stillFailed.size > 10) "\n... 외 ${stillFailed.size - 10}개" else ""
                )
            }
        }

        mrpackZip.delete()
        Log.d("TURTLE_LAUNCHER", "✅ .mrpack 설치 완료: $displayName, MC $mcVersion, $loaderType $loaderVersion")

        return ModPackInstallResult(
            success = true,
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            gameDir = gameDir
        )
    }

    /** 클라이언트에 필요한 파일만(서버 전용 제외). env 가 없으면 포함. */
    private fun isClientWanted(f: MrpackFile): Boolean {
        val client = f.env?.client ?: return true
        return client != "unsupported"
    }

    /**
     * .mrpack dependencies 맵 → (loaderType, loaderVersion)
     *   키: minecraft / forge / neoforge / fabric-loader / quilt-loader
     *   TurtleLauncher 의 loaderType 표기(fabric/forge/neoforge/quilt)에 맞춘다.
     */
    private fun resolveLoader(deps: Map<String, String>): Pair<String?, String?> {
        deps["fabric-loader"]?.let { return "fabric" to it }
        deps["quilt-loader"]?.let { return "quilt" to it }
        deps["forge"]?.let { return "forge" to it }
        deps["neoforge"]?.let { return "neoforge" to it }
        return null to null
    }

    private fun extractIndex(zipFile: File): MrpackIndex? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("modrinth.index.json") ?: run {
                    Log.e("TURTLE_LAUNCHER", "modrinth.index.json 없음")
                    return null
                }
                val json = zip.getInputStream(entry).bufferedReader().readText()
                gson.fromJson(json, MrpackIndex::class.java)
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "modrinth.index.json 파싱 실패: ${e.message}")
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
                        if (relativePath.isBlank()) return@forEach
                        val destFile = File(gameDir, relativePath)
                        destFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "overrides 추출 실패($overridesFolder): ${e.message}")
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        onBytes: ((downloaded: Long, total: Long, speedBps: Double) -> Unit)? = null,
    ) {
        if (destFile.exists() && destFile.length() > 0) return
        destFile.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Endiq-jar/TurtleLauncher/1.0 (com.endiq.turtlelauncher)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("다운로드 실패: $url (${response.code})")
            val body = response.body ?: throw Exception("응답 본문 없음: $url")
            val contentLen = body.contentLength()

            if (onBytes == null) {
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { input.copyTo(it) }
                }
                return
            }

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

    /** 바이트/속도 → "12.3 MB / 45.0 MB · 8.4 MB/s" 라벨(파일명 뒤 상세). */
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