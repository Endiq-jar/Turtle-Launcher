package kr.co.donghyun.flamelauncher.presentation.util.maps

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 외부 zip 으로 받은 마인크래프트 맵(월드)을 인스턴스의 saves/ 폴더로 가져온다.
 *
 * Android 11+ Scoped Storage 정책:
 *   - 사용자/타 앱은 우리 앱의 Android/data/.../saves/ 에 직접 파일을 넣을 수 없다.
 *   - 그러나 "우리 앱 자신"은 자기 전용 외부 디렉토리에 자유롭게 쓸 수 있다(권한 불필요).
 *   - 따라서 사용자가 SAF(파일 피커)로 zip 을 고르면, 그 URI 를 우리가 읽어
 *     saves/ 에 직접 압축 해제하는 방식이 정책상 올바른 길이다.
 *
 * 이 유틸은 UI 와 독립적이며 IO 스레드에서 호출되어야 한다(코루틴 Dispatchers.IO).
 */
object MapImporter {

    private const val TAG = "PING_MAP_IMPORT"

    sealed class Result {
        data class Success(val worldName: String, val fileCount: Int) : Result()
        data class Failure(val reason: String, val cause: Throwable? = null) : Result()
    }

    /**
     * @param context     앱 컨텍스트
     * @param zipUri       SAF 로 사용자가 고른 zip 의 URI
     * @param savesDir     대상 saves 디렉토리 (예: <instanceDir>/saves)
     * @param onProgress   (선택) 진행 콜백 — (현재까지 푼 엔트리 수)
     */
    fun importZip(
        context: Context,
        zipUri: Uri,
        savesDir: File,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        return try {
            savesDir.mkdirs()

            // 1) zip 안에서 'level.dat' 의 위치를 찾아 맵 루트(prefix)를 판단한다.
            //    - zip 루트에 바로 level.dat 가 있으면 prefix = ""  → saves/<파일명>/ 으로 풂
            //    - "MyMap/level.dat" 처럼 한 겹 폴더에 있으면 prefix = "MyMap/" → saves/MyMap/ 으로 풂
            //    - 더 깊이("a/b/level.dat") 있으면 그 부모를 맵 루트로 사용
            val levelDatPath = findLevelDatPath(context, zipUri)
                ?: return Result.Failure("zip 안에서 level.dat 를 찾지 못했습니다. 올바른 마인크래프트 맵 zip 인지 확인하세요.")

            val rootPrefix = levelDatPath.substringBeforeLast('/', "")
                .let { if (it.isEmpty()) "" else "$it/" }

            // 2) 대상 월드 폴더 이름 결정
            val worldName = if (rootPrefix.isEmpty()) {
                // zip 루트에 level.dat → zip 파일명을 월드 이름으로
                sanitizeName(queryDisplayName(context, zipUri)?.removeSuffix(".zip") ?: "ImportedWorld")
            } else {
                // 감싼 폴더명을 월드 이름으로
                sanitizeName(rootPrefix.trimEnd('/').substringAfterLast('/'))
            }

            // 3) 중복 이름이면 뒤에 번호 붙이기 (기존 월드 보호)
            val targetDir = uniqueDir(savesDir, worldName)

            // 4) 압축 해제 (zip-slip 방어 포함)
            var count = 0
            openZip(context, zipUri).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // 맵 루트 밖의 엔트리는 무시 (여러 폴더가 섞인 zip 안전 처리)
                    if (rootPrefix.isNotEmpty() && !name.startsWith(rootPrefix)) {
                        zis.closeEntry(); entry = zis.nextEntry; continue
                    }
                    val rel = if (rootPrefix.isEmpty()) name else name.removePrefix(rootPrefix)
                    if (rel.isEmpty()) { zis.closeEntry(); entry = zis.nextEntry; continue }

                    val outFile = File(targetDir, rel)

                    // ── zip-slip 방어: 정규화된 경로가 targetDir 밖이면 거부 ──
                    val canonicalTarget = targetDir.canonicalPath
                    val canonicalOut = outFile.canonicalPath
                    if (!canonicalOut.startsWith(canonicalTarget + File.separator) &&
                        canonicalOut != canonicalTarget) {
                        throw SecurityException("의심스러운 zip 엔트리 경로(zip slip): $name")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out, 8192) }
                        count++
                        onProgress?.invoke(count)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 5) 검증 — 푼 결과에 실제로 level.dat 가 있는지
            if (!File(targetDir, "level.dat").exists()) {
                // 정리하고 실패 처리
                targetDir.deleteRecursively()
                return Result.Failure("압축 해제 후 level.dat 가 확인되지 않았습니다.")
            }

            Log.i(TAG, "맵 가져오기 성공: ${targetDir.absolutePath} ($count files)")
            Result.Success(worldName = targetDir.name, fileCount = count)
        } catch (e: SecurityException) {
            Log.e(TAG, "맵 가져오기 보안 거부: ${e.message}", e)
            Result.Failure("안전하지 않은 zip 으로 판단되어 중단했습니다.", e)
        } catch (e: Exception) {
            Log.e(TAG, "맵 가져오기 실패: ${e.message}", e)
            Result.Failure("맵 가져오기 중 오류가 발생했습니다: ${e.message}", e)
        }
    }

    /** zip 을 한 번 훑어 첫 번째 level.dat 의 전체 경로를 찾는다. 없으면 null. */
    private fun findLevelDatPath(context: Context, zipUri: Uri): String? {
        openZip(context, zipUri).use { zis ->
            var entry = zis.nextEntry
            var shallowest: String? = null
            var shallowestDepth = Int.MAX_VALUE
            while (entry != null) {
                val n = entry.name
                if (!entry.isDirectory && n.substringAfterLast('/') == "level.dat") {
                    // 여러 개면 가장 얕은(상위) 것을 맵 루트로 — 보통 맵 루트의 level.dat
                    val depth = n.count { it == '/' }
                    if (depth < shallowestDepth) {
                        shallowestDepth = depth
                        shallowest = n
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            return shallowest
        }
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

    /** 파일시스템에 안전한 폴더명으로 정리 */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")   // 금지 문자 치환
            .replace(Regex("\\s+"), " ")
            .take(64)
        return cleaned.ifBlank { "ImportedWorld" }
    }

    /** 이미 같은 이름의 월드가 있으면 " (2)", " (3)" ... 붙여 충돌 방지 */
    private fun uniqueDir(savesDir: File, baseName: String): File {
        var candidate = File(savesDir, baseName)
        var i = 2
        while (candidate.exists()) {
            candidate = File(savesDir, "$baseName ($i)")
            i++
        }
        candidate.mkdirs()
        return candidate
    }
}