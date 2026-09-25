package kr.co.donghyun.flamelauncher.data.renderer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 사용자가 K11MCH1/AdrenoToolsDrivers 같은 곳에서 받은 표준 포맷의 커스텀 Vulkan
 * 드라이버(Turnip 등) zip 을 가져와서 관리한다. 이 표준 포맷(zip 안에 .so 파일들 +
 * meta.json)은 Winlator/Eden/Skyline 등 여러 안드로이드 에뮬레이터가 공유하는
 * "AdrenoTools 호환" 규격이라, 커뮤니티에서 받은 드라이버를 그대로 쓸 수 있다.
 *
 * ⚠️ libadrenotools 자체는 드라이버 바이너리를 만들지 않는다 — 그건 사용자가
 * 직접 신뢰할 만한 출처(Mesa 공식/K11MCH1 등)에서 받아와야 한다. 이 클래스는
 * "가져오기 → 목록 관리 → 선택"만 담당한다.
 */
object CustomVulkanDriverManager {

    data class DriverInfo(
        val id: String,          // 폴더명(=드라이버 식별자)
        val displayName: String, // meta.json 의 name (없으면 id)
        val libraryName: String, // meta.json 의 libraryName(.so 파일명) — 필수
    )

    private const val PREFS = "custom_vulkan_driver"
    private const val KEY_ACTIVE_DRIVER_ID = "active_driver_id"

    private fun driversRoot(context: Context): File =
        File(context.filesDir, "vulkan_drivers").apply { mkdirs() }

    /**
     * 드라이버 zip(Uri, SAF 로 선택됨)을 가져와서 압축 해제하고 meta.json 을 읽는다.
     * @return 성공 시 DriverInfo, 실패 시 null(사유는 로그로 확인)
     */
    fun importDriverZip(context: Context, zipUri: Uri): DriverInfo? {
        val id = "driver_${System.currentTimeMillis()}"
        val targetDir = File(driversRoot(context), id)
        targetDir.mkdirs()

        return try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            // zip slip 방지 — 경로 이탈 항목은 건너뜀.
                            val outFile = File(targetDir, File(entry.name).name)
                            outFile.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: run {
                targetDir.deleteRecursively()
                return null
            }

            val metaFile = File(targetDir, "meta.json")
            if (!metaFile.exists()) {
                Log.e("FLAME_LAUNCHER", "커스텀 드라이버 zip 안에 meta.json 이 없음")
                targetDir.deleteRecursively()
                return null
            }
            val meta = JsonParser.parseString(metaFile.readText()).asJsonObject
            val libraryName = meta.get("libraryName")?.asString
            if (libraryName.isNullOrBlank() || !File(targetDir, libraryName).exists()) {
                Log.e("FLAME_LAUNCHER", "meta.json 의 libraryName 이 없거나 해당 .so 파일이 없음")
                targetDir.deleteRecursively()
                return null
            }
            val displayName = meta.get("name")?.asString ?: id

            DriverInfo(id = id, displayName = displayName, libraryName = libraryName)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "커스텀 드라이버 가져오기 실패: ${e.message}", e)
            targetDir.deleteRecursively()
            null
        }
    }

    /** 이미 가져온 드라이버 목록(폴더별로 meta.json 다시 읽음). */
    fun listDrivers(context: Context): List<DriverInfo> {
        val root = driversRoot(context)
        return root.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val meta = JsonParser.parseString(metaFile.readText()).asJsonObject
                val libraryName = meta.get("libraryName")?.asString ?: return@mapNotNull null
                DriverInfo(
                    id = dir.name,
                    displayName = meta.get("name")?.asString ?: dir.name,
                    libraryName = libraryName,
                )
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()
    }

    fun deleteDriver(context: Context, driverId: String) {
        File(driversRoot(context), driverId).deleteRecursively()
        if (getActiveDriverId(context) == driverId) setActiveDriverId(context, null)
    }

    fun getActiveDriverId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_DRIVER_ID, null)

    fun setActiveDriverId(context: Context, driverId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_DRIVER_ID, driverId)
            .apply()
    }

    /** 현재 활성화된 드라이버의 (폴더경로, .so 파일명) — 없으면 null(시스템 기본 사용). */
    fun getActiveDriverPathAndName(context: Context): Pair<String, String>? {
        val activeId = getActiveDriverId(context) ?: return null
        val dir = File(driversRoot(context), activeId)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val meta = JsonParser.parseString(metaFile.readText()).asJsonObject
            val libraryName = meta.get("libraryName")?.asString ?: return null
            dir.absolutePath to libraryName
        } catch (_: Exception) {
            null
        }
    }
}
