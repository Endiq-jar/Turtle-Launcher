package kr.co.donghyun.flamelauncher.presentation.util.mods

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * .jar 모드 파일을 인스턴스의 mods/ 폴더로 복사한다.
 * MapImporter(월드 zip) 와 같은 모양의 Result 를 돌려준다.
 *
 *  - PK(zip) 매직바이트로 1차 검증 (jar 은 zip 컨테이너)
 *  - 동일 파일명이 있으면 덮어쓴다(= 모드 업데이트)
 *  - 공유로 들어온 파일이 확장자가 없거나 .zip 이어도, mods 로 넣을 땐 .jar 로 보정
 *    (Forge/Fabric 의 mods 스캐너는 .jar 확장자만 로드함)
 */
object ModImporter {

    sealed class Result {
        /** added: 실제로 추가된 파일명, skipped: "이름 (이유)" 형태 */
        data class Success(val added: List<String>, val skipped: List<String>) : Result()
        data class Failure(val reason: String) : Result()
    }

    fun importJars(context: Context, uris: List<Uri>, modsDir: File): Result {
        if (uris.isEmpty()) return Result.Failure("선택된 파일이 없습니다.")
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            return Result.Failure("mods 폴더를 만들 수 없습니다: ${modsDir.absolutePath}")
        }

        val added = ArrayList<String>()
        val skipped = ArrayList<String>()

        for (uri in uris) {
            val rawName = queryDisplayName(context, uri) ?: "mod_${System.currentTimeMillis()}.jar"
            val safeName = sanitizeJarName(rawName)
            try {
                // 1차 검증: zip(jar) 매직바이트 "PK"
                val looksLikeZip = context.contentResolver.openInputStream(uri)?.use { input ->
                    val head = ByteArray(2)
                    input.read(head) == 2 &&
                            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                } ?: false
                if (!looksLikeZip) {
                    skipped.add("$safeName (jar/zip 형식이 아님)")
                    continue
                }

                val dest = File(modsDir, safeName)
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    skipped.add("$safeName (파일을 열 수 없음)")
                    continue
                }
                input.use { FileOutputStream(dest).use { out -> it.copyTo(out) } }

                if (dest.exists() && dest.length() > 0) added.add(safeName)
                else skipped.add("$safeName (복사 실패)")
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "모드 복사 실패: $safeName", e)
                skipped.add("$safeName (${e.message ?: "오류"})")
            }
        }

        return if (added.isEmpty() && skipped.isNotEmpty())
            Result.Failure("추가된 모드가 없습니다.\n" + skipped.joinToString("\n"))
        else
            Result.Success(added, skipped)
    }

    /** content:// 의 표시 이름 조회. 실패하면 lastPathSegment 로 폴백. */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val fromResolver = try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** 경로 구분자 제거 + .jar 확장자 보정 */
    private fun sanitizeJarName(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (n.isEmpty()) n = "mod_${System.currentTimeMillis()}"
        if (!n.endsWith(".jar", ignoreCase = true)) {
            n = n.substringBeforeLast('.', n) + ".jar"
        }
        return n
    }
}