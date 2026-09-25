package kr.co.donghyun.flamelauncher.presentation.util.resources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 외부 zip 으로 받은 마인크래프트 리소스팩을 인스턴스의 resourcepacks/ 폴더로 가져온다.
 *
 * 맵(MapImporter)과의 차이:
 *   - 맵은 saves/<world>/ 로 "압축을 풀어" 넣어야 한다(폴더 구조 필요).
 *   - 리소스팩은 마인크래프트가 .zip 파일 자체를 읽으므로, 풀지 않고 .zip 그대로 복사한다.
 *     (resourcepacks/ 아래에 zip 파일을 그대로 두면 게임의 "리소스팩" 화면에 나타난다.)
 *
 * Android 11+ Scoped Storage 정책:
 *   - 사용자/타 앱은 우리 앱의 Android/data/.../resourcepacks/ 에 직접 파일을 넣을 수 없다.
 *   - 그러나 "우리 앱 자신"은 자기 전용 외부 디렉토리에 자유롭게 쓸 수 있다(권한 불필요).
 *   - 따라서 사용자가 SAF(파일 피커)로 zip 을 고르면, 그 URI 를 우리가 읽어
 *     resourcepacks/ 에 직접 복사하는 방식이 정책상 올바른 길이다.
 *
 * 이 유틸은 UI 와 독립적이며 IO 스레드에서 호출되어야 한다(코루틴 Dispatchers.IO).
 */
object ResourcePackImporter {

    private const val TAG = "PING_RP_IMPORT"

    sealed class Result {
        data class Success(val packName: String) : Result()
        data class Failure(val reason: String, val cause: Throwable? = null) : Result()
    }

    /**
     * @param context          앱 컨텍스트
     * @param zipUri           SAF 로 사용자가 고른 zip 의 URI
     * @param resourcePacksDir 대상 resourcepacks 디렉토리 (예: <instanceDir>/resourcepacks)
     */
    fun importZip(
        context: Context,
        zipUri: Uri,
        resourcePacksDir: File,
    ): Result {
        return try {
            resourcePacksDir.mkdirs()

            // 1) 올바른 리소스팩인지 가볍게 검증 — zip 안에 pack.mcmeta 가 있어야 한다.
            //    (루트 또는 한 겹 폴더 안 어디든 pack.mcmeta 가 있으면 통과)
            if (!hasPackMcmeta(context, zipUri)) {
                return Result.Failure(
                    "zip 안에서 pack.mcmeta 를 찾지 못했습니다. 올바른 리소스팩 zip 인지 확인하세요."
                )
            }

            // 2) 대상 파일명 결정 (원본 표시 이름 → 없으면 기본값). 확장자는 .zip 으로 보장.
            val rawName = queryDisplayName(context, zipUri) ?: "resource_pack.zip"
            val baseName = sanitizeFileName(
                if (rawName.endsWith(".zip", ignoreCase = true)) rawName else "$rawName.zip"
            )

            // 3) 중복 이름이면 " (2)", " (3)" … 붙이기 (기존 팩 보호)
            val targetFile = uniqueFile(resourcePacksDir, baseName)

            // 4) zip 그대로 복사 (압축 해제하지 않음)
            context.contentResolver.openInputStream(zipUri).use { input ->
                if (input == null) {
                    return Result.Failure("zip 스트림을 열 수 없습니다 (URI 접근 실패).")
                }
                targetFile.outputStream().use { out ->
                    input.copyTo(out, 64 * 1024)
                }
            }

            // 5) 검증 — 복사 결과가 비어있지 않은지
            if (!targetFile.exists() || targetFile.length() == 0L) {
                targetFile.delete()
                return Result.Failure("복사된 리소스팩 파일이 비어 있습니다.")
            }

            Log.i(TAG, "리소스팩 가져오기 성공: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            Result.Success(packName = targetFile.name)
        } catch (e: Exception) {
            Log.e(TAG, "리소스팩 가져오기 실패: ${e.message}", e)
            Result.Failure("리소스팩 가져오기 중 오류가 발생했습니다: ${e.message}", e)
        }
    }

    /** zip 을 한 번 훑어 어딘가에 pack.mcmeta 가 있는지 확인한다. */
    private fun hasPackMcmeta(context: Context, zipUri: Uri): Boolean {
        openZip(context, zipUri).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val leaf = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && leaf == "pack.mcmeta") {
                    return true
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return false
    }

    private fun openZip(context: Context, zipUri: Uri): ZipInputStream {
        val input: InputStream = context.contentResolver.openInputStream(zipUri)
            ?: throw IllegalStateException("zip 스트림을 열 수 없습니다 (URI 접근 실패)")
        return ZipInputStream(BufferedInputStream(input))
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    /** 파일시스템에 안전한 파일명으로 정리 (.zip 확장자는 유지) */
    private fun sanitizeFileName(raw: String): String {
        val hasZip = raw.endsWith(".zip", ignoreCase = true)
        val stem = if (hasZip) raw.dropLast(4) else raw
        val cleanedStem = stem.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")   // 금지 문자 치환
            .replace(Regex("\\s+"), " ")
            .take(96)
            .ifBlank { "resource_pack" }
        return "$cleanedStem.zip"
    }

    /** 이미 같은 이름의 팩이 있으면 " (2)", " (3)" … 붙여 충돌 방지 (.zip 앞에 번호) */
    private fun uniqueFile(dir: File, baseName: String): File {
        var candidate = File(dir, baseName)
        if (!candidate.exists()) return candidate
        val stem = baseName.dropLast(4) // ".zip"
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($i).zip")
            i++
        }
        return candidate
    }
}