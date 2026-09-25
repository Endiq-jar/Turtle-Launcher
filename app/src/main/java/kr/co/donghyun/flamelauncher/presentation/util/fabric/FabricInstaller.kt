package kr.co.donghyun.flamelauncher.presentation.util.fabric


import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class FabricInstallResult(
    val success: Boolean,
    val mainClass: String = "",
    val extraJars: List<String> = emptyList(),
    val gameJvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Fabric loader를 [instanceDir]/libraries/ 아래에 머지 설치한다.
 * 반드시 바닐라 다운로드를 먼저 끝낸 같은 인스턴스 디렉토리에서 호출할 것.
 */
class FabricInstaller(
    private val instanceDir: File,
    private val onProgress: (phase: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
) {
    private val client = OkHttpClient()
    private val api = FabricMetaAPI()

    fun install(mcVersion: String, loaderVersion: String): FabricInstallResult {
        return try {
            onProgress("Fabric 프로필 가져오는 중...", 0, 0)
            val raw = api.fetchProfile(mcVersion, loaderVersion)
            // 인스턴스 디렉토리에 프로필도 캐싱 (디버그용)
            File(instanceDir, "fabric-profile.json").also {
                it.parentFile?.mkdirs()
                it.writeText(raw)
            }

            val profile = JsonParser.parseString(raw).asJsonObject
            val mainClass = profile["mainClass"]?.asString
                ?: return FabricInstallResult(success = false, error = "mainClass 누락")

            val librariesDir = File(instanceDir, "libraries")
            val jarList = mutableListOf<String>()
            val libs = profile["libraries"]?.asJsonArray
                ?: return FabricInstallResult(success = false, error = "libraries 누락")

            val total = libs.size()
            libs.forEachIndexed { i, el ->
                val lib = el.asJsonObject
                val name = lib["name"]?.asString ?: return@forEachIndexed
                val baseUrl = lib["url"]?.asString ?: "https://maven.fabricmc.net/"

                onProgress("Fabric 라이브러리 ${i + 1}/$total", i + 1, total)

                val path = mavenNameToPath(name)
                val destFile = File(librariesDir, path)

                if (!destFile.exists() || destFile.length() == 0L) {
                    destFile.parentFile?.mkdirs()
                    val primary = if (baseUrl.endsWith("/")) "$baseUrl$path" else "$baseUrl/$path"
                    val ok = tryDownload(primary, destFile) ||
                            tryDownload("https://maven.fabricmc.net/$path", destFile) ||
                            tryDownload("https://libraries.minecraft.net/$path", destFile)
                    if (!ok) {
                        Log.w("FLAME_LAUNCHER", "Fabric 라이브러리 실패: $name")
                    }
                }
                if (destFile.exists() && destFile.length() > 0) {
                    jarList.add(destFile.absolutePath)
                }
            }

            val gameJvmArgs = mutableListOf<String>()
            val gameArgs = mutableListOf<String>()
            profile["arguments"]?.asJsonObject?.let { args ->
                args["jvm"]?.asJsonArray?.forEach { addStringArg(it, gameJvmArgs) }
                args["game"]?.asJsonArray?.forEach { addStringArg(it, gameArgs) }
            }

            Log.d("FLAME_LAUNCHER", "✅ Fabric 설치: mainClass=$mainClass, jars=${jarList.size}, jvmArgs=$gameJvmArgs")

            FabricInstallResult(
                success = true,
                mainClass = mainClass,
                extraJars = jarList,
                gameJvmArgs = gameJvmArgs,
                gameArgs = gameArgs
            )
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "Fabric 설치 예외", e)
            FabricInstallResult(success = false, error = e.message ?: "알 수 없는 오류")
        }
    }

    /** Mojang 스타일 conditional 객체는 무시 — 단순 문자열만 채택 */
    private fun addStringArg(el: JsonElement, out: MutableList<String>) {
        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) out.add(el.asString)
    }

    private fun tryDownload(url: String, dest: File): Boolean = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            true
        }
    } catch (_: Exception) { false }

    private fun mavenNameToPath(name: String): String {
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val versionFull = parts[2]
        val classifier = if (parts.size > 3) "-${parts[3]}" else ""
        return "$group/$artifact/$versionFull/$artifact-$versionFull$classifier.jar"
    }
}