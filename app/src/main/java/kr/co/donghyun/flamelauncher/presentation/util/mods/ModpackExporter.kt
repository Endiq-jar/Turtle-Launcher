package kr.co.donghyun.flamelauncher.presentation.util.mods

import android.content.Context
import android.net.Uri
import kr.co.donghyun.flamelauncher.data.instance.InstanceMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ModpackExporter {

    sealed interface Result {
        data class Success(val modCount: Int, val configCount: Int) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * @param outputUri  SAF CreateDocument 로 사용자가 고른 저장 위치
     * @param modsDir    내보낼 mods 폴더 (gameDir/mods)
     * @param configDir  내보낼 config 폴더 (gameDir/config). 없으면 null.
     * @param meta       인스턴스 메타(없으면 manifest 에 기본값)
     * @param displayName 메타가 없을 때 쓸 표시 이름
     */
    fun export(
        context: Context,
        outputUri: Uri,
        modsDir: File,
        configDir: File?,
        meta: InstanceMeta?,
        displayName: String,
    ): Result {
        return try {
            val modFiles = modsDir
                .listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (modFiles.isEmpty()) {
                return Result.Failure("내보낼 모드가 없습니다. mods 폴더가 비어 있어요.")
            }

            val manifest = buildManifest(meta, displayName, modFiles.map { it.name })

            val out = context.contentResolver.openOutputStream(outputUri)
                ?: return Result.Failure("저장 위치를 열 수 없습니다.")

            var configCount = 0
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
// manifest.json
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

// mods/*.jar
                for (jar in modFiles) {
                    zip.putNextEntry(ZipEntry("mods/${jar.name}"))
                    jar.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

// config/** (있으면 통째로)
                if (configDir != null && configDir.isDirectory) {
                    configCount = addDirRecursively(zip, configDir, "config")
                }
            }

            Result.Success(modFiles.size, configCount)
        } catch (e: Exception) {
            Result.Failure("내보내기 실패: ${e.message}")
        }
    }

    /** dir 안의 모든 파일을 zip 에 prefix 아래로 추가하고, 추가한 파일 수를 반환. */
    private fun addDirRecursively(zip: ZipOutputStream, dir: File, prefix: String): Int {
        var count = 0
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            val entryPath = "$prefix/${f.name}"
            if (f.isDirectory) {
                count += addDirRecursively(zip, f, entryPath)
            } else if (f.isFile) {
                zip.putNextEntry(ZipEntry(entryPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    private fun buildManifest(
        meta: InstanceMeta?,
        displayName: String,
        modNames: List<String>,
    ): String {
        val obj = JSONObject()
        obj.put("flameLauncherModpack", 1)
        obj.put("name", meta?.name ?: displayName)
        obj.put("mcVersion", meta?.mcVersion ?: "")
        obj.put("loaderType", meta?.loaderType ?: JSONObject.NULL)
        obj.put("loaderVersion", meta?.loaderVersion ?: JSONObject.NULL)
        obj.put("iconEmoji", meta?.iconEmoji ?: "📦")
        obj.put("exportedAt", System.currentTimeMillis())
        val arr = JSONArray()
        modNames.forEach { arr.put(it) }
        obj.put("mods", arr)
        return obj.toString(2)
    }
}