package kr.co.donghyun.turtlelauncher.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.co.donghyun.turtlelauncher.BuildConfig
import kr.co.donghyun.turtlelauncher.data.instance.InstanceManager
import kr.co.donghyun.turtlelauncher.data.jvm.isLegacyVersion
import kr.co.donghyun.turtlelauncher.data.mods.ContentSource
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeFile
import kr.co.donghyun.turtlelauncher.data.mods.CurseForgeListResponse
import kr.co.donghyun.turtlelauncher.data.mods.ModrinthVersion
import kr.co.donghyun.turtlelauncher.data.util.markdownToHtml
import kr.co.donghyun.turtlelauncher.presentation.ContentDetail
import kr.co.donghyun.turtlelauncher.presentation.ContentScreenshot
import kr.co.donghyun.turtlelauncher.presentation.InstanceSummary
import kr.co.donghyun.turtlelauncher.presentation.ModLoader
import kr.co.donghyun.turtlelauncher.presentation.VersionLoaderCombo
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.turtlelauncher.presentation.util.mods.ModrinthAPI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 콘텐츠 상세(ContentPackDetailActivity) 화면의 읽기 전용 데이터 로직.
 * ContentPackDetailActivity 에 있던 상세조회/버전-로더 감지/인스턴스 스캔 함수들을
 * 그대로 이관했다(어댑터 방식). 다이얼로그 플로우·상태(handleInstallRequest 포함)는
 * ContentDetailViewModel 이 담당한다.
 */
@Singleton
class ContentDetailRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val MC_VERSION_REGEX = Regex("""^\d+\.\d+(\.\d+)?$""")

    fun fetchModrinthDetail(projectId: String): ContentDetail {
        val project = ModrinthAPI().getProject(projectId) ?: return ContentDetail()
        val shots = project.gallery.map { img ->
            ContentScreenshot(thumbnailUrl = img.url, fullUrl = img.url)
        }
        // body 는 마크다운(+원시 HTML) — HTML 뷰어(WebView)용으로 변환해 이미지/태그까지 렌더.
        val html = markdownToHtml(project.body)
        // rawHtml 을 못 쓰는 자리를 위한 평문 폴백(가벼운 평문화, 완전 변환은 아님)
        val desc = project.body
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")     // 이미지 마크다운 제거
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")  // 링크 → 텍스트
            .replace(Regex("[*_`>#]+"), "")                       // 마크다운 기호 제거
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { project.description }
        return ContentDetail(screenshots = shots, description = desc, rawHtml = html)
    }

    /**
     * 인스턴스의 월드(세이브) 목록. level.dat 가 있는 폴더만 월드로 본다.
     * 데이터팩 설치 대상 선택에 쓴다.
     */
    fun listWorldsForInstance(instance: InstanceSummary): List<String> {
        val instanceDir = InstanceManager.instanceDir(context, instance.id)
        val savesDir = if (isLegacyVersion(instance.gameVersion))
            File(instanceDir, ".minecraft/saves")
        else
            File(instanceDir, "saves")
        if (!savesDir.isDirectory) return emptyList()
        return savesDir.listFiles()
            ?.filter { it.isDirectory && File(it, "level.dat").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 감지용 파일 목록 1회 페치. 로더/버전/조합 추출에 공통으로 쓴다.
     * 네트워크 실패 / 비어있음 → emptyList (각 추출 함수가 폴백 처리).
     */
    fun fetchFilesForDetect(modId: Int): List<CurseForgeFile> {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/files?pageSize=50&index=0")
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return emptyList()
                val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>(){}.type
                Gson().fromJson<CurseForgeListResponse<CurseForgeFile>>(body, type).data
            }
        } catch (e: Exception) {
            Log.w("TURTLE_LAUNCHER", "파일 감지 페치 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * 파일 목록의 gameVersions 로더 태그를 모은다.
     *  - 네트워크 실패 / 응답 비어있음 → emptySet (호출자가 폴백 처리)
     */
    fun extractSupportedLoaders(files: List<CurseForgeFile>): Set<ModLoader> {
        val out = mutableSetOf<ModLoader>()
        files.forEach { f ->
            f.gameVersions.forEach { gv ->
                when (gv.lowercase()) {
                    "forge"    -> out += ModLoader.FORGE
                    "fabric"   -> out += ModLoader.FABRIC
                    "neoforge" -> out += ModLoader.NEOFORGE
                    // Quilt 는 ModLoader enum 에 없어서 skip
                }
            }
        }
        Log.d("TURTLE_LAUNCHER", "🔍 supported loaders: $out (files=${files.size})")
        return out
    }

    /**
     * 파일 목록에서 이 콘텐츠가 지원하는 MC 버전 집합을 모은다.
     * gameVersions 에는 로더 태그("Forge")와 MC 버전("1.20.1")이 섞여 있으므로,
     * "1.x" / "1.x.y" 형태(숫자.숫자[.숫자])만 골라낸다.
     */
    fun extractSupportedMcVersions(files: List<CurseForgeFile>): Set<String> {
        val out = mutableSetOf<String>()
        files.forEach { f ->
            f.gameVersions.forEach { gv ->
                if (MC_VERSION_REGEX.matches(gv.trim())) out += gv.trim()
            }
        }
        Log.d("TURTLE_LAUNCHER", "🔍 supported MC versions: $out (files=${files.size})")
        return out
    }

    /**
     * 파일 목록에서 (MC 버전 × 로더) 조합을 전부 뽑아 중복 제거 후 최신순 정렬.
     *
     * 한 파일의 gameVersions 안에는 MC 버전 여러 개 + 로더 태그 여러 개가 섞여 있을 수 있다.
     * 따라서 파일별로 (그 파일의 MC 버전들) × (그 파일의 로더들) 을 곱해 조합을 만든다.
     *  - 로더 태그가 전혀 없는 파일:
     *      · allowVanilla=true  → 로더 null(바닐라) 조합으로 추가 (텍스처/쉐이더/월드 등)
     *      · allowVanilla=false → 로더를 특정할 수 없으므로 건너뜀
     *  - 네트워크 실패로 files 가 비면 emptyList → 다이얼로그가 정적 폴백 목록 사용
     */
    fun extractVersionLoaderCombos(
        files: List<CurseForgeFile>,
        allowVanilla: Boolean,
    ): List<VersionLoaderCombo> {
        val combos = linkedSetOf<Pair<String, ModLoader?>>()  // 삽입 순서 유지 + 중복 제거

        files.forEach { f ->
            val versions = f.gameVersions.map { it.trim() }.filter { MC_VERSION_REGEX.matches(it) }
            if (versions.isEmpty()) return@forEach

            val loaders = f.gameVersions.mapNotNull { gv ->
                when (gv.lowercase()) {
                    "forge"    -> ModLoader.FORGE
                    "fabric"   -> ModLoader.FABRIC
                    "neoforge" -> ModLoader.NEOFORGE
                    else       -> null
                }
            }.distinct()

            when {
                loaders.isNotEmpty() ->
                    versions.forEach { v -> loaders.forEach { l -> combos += v to l } }
                allowVanilla ->
                    versions.forEach { v -> combos += v to null }
                // 로더도 없고 바닐라도 불허 → 스킵 (모드인데 로더 태그 누락된 비정상 파일)
            }
        }

        val result = combos.map { (v, l) -> VersionLoaderCombo(v, l, mcVersionSortKey(v)) }
            // 최신 버전 먼저, 같은 버전이면 로더 enum 순서(Fabric→Forge→NeoForge)
            .sortedWith(compareByDescending<VersionLoaderCombo> { it.sortKey }
                .thenBy { it.loader?.ordinal ?: -1 })

        Log.d("TURTLE_LAUNCHER", "🔍 version×loader combos: ${result.map { it.label }} (files=${files.size})")
        return result
    }

    /** "1.20.1" → 비교키(major*10000 + minor*100 + patch). 정렬 전용. */
    fun mcVersionSortKey(v: String): Int {
        val p = v.split(".").mapNotNull { it.toIntOrNull() }
        return (p.getOrElse(0) { 0 } * 10000) + (p.getOrElse(1) { 0 } * 100) + p.getOrElse(2) { 0 }
    }

    /**
     * 설치된 인스턴스 목록. [includeVanilla]=true면 로더 없는 바닐라도 포함.
     */
    fun scanInstances(
        includeVanilla: Boolean,
        supportedLoaders: Set<ModLoader> = emptySet(),
        supportedMcVersions: Set<String> = emptySet(),   // 비어있지 않으면 이 MC 버전만 통과
    ): List<InstanceSummary> {
        return try {
            InstanceManager.listInstances(context).mapNotNull { meta ->
                val loader = when (meta.loaderType?.lowercase()) {
                    "fabric"   -> ModLoader.FABRIC
                    "forge"    -> ModLoader.FORGE
                    "neoforge" -> ModLoader.NEOFORGE
                    else       -> null   // Vanilla
                }

                when {
                    // 바닐라 인스턴스
                    loader == null -> {
                        if (!includeVanilla) return@mapNotNull null
                    }
                    // 로더 인스턴스 — supportedLoaders 가 비어있으면 (감지 실패)
                    // 보수적으로 전부 통과시킴. 아니면 교집합만 통과.
                    supportedLoaders.isNotEmpty() && loader !in supportedLoaders ->
                        return@mapNotNull null
                }

                // MC 버전 필터 — supportedMcVersions 가 비어있지 않을 때만 적용.
                // (데이터팩 등 버전 민감 콘텐츠. 감지 실패 시엔 비어있어 필터 미적용)
                if (supportedMcVersions.isNotEmpty() && meta.mcVersion !in supportedMcVersions) {
                    return@mapNotNull null
                }

                InstanceSummary(
                    id = meta.id,
                    name = meta.name,
                    gameVersion = meta.mcVersion,
                    loader = loader
                )
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "인스턴스 스캔 실패: ${e.message}")
            emptyList()
        }
    }

    fun fetchModDetail(modId: Int): ContentDetail {
        val client = OkHttpClient()
        val apiKey = BuildConfig.CURSEFORGE_API_KEY
        var screenshots = listOf<ContentScreenshot>()
        var description = ""
        var rawHtmlWebView = ""

        val modRequest = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        client.newCall(modRequest).execute().use { response ->
            val json = response.body?.string() ?: return@use
            val data = JsonParser.parseString(json)
                .asJsonObject["data"]?.asJsonObject ?: return@use

            screenshots = data["screenshots"]?.asJsonArray?.mapNotNull { el ->
                val obj = el.asJsonObject
                val full = obj["url"]?.asString ?: return@mapNotNull null
                val thumb = "$full?width=400&height=225"
                ContentScreenshot(thumbnailUrl = thumb, fullUrl = full)
            } ?: emptyList()
        }

        val descRequest = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/description")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        client.newCall(descRequest).execute().use { response ->
            val json = response.body?.string() ?: return@use
            val rawHtml = JsonParser.parseString(json)
                .asJsonObject["data"]?.asString ?: ""
            rawHtmlWebView = rawHtml
            description = rawHtml
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
                .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<div[^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        return ContentDetail(screenshots = screenshots, description = description, rawHtml = rawHtmlWebView)
    }

    fun isContentInstalled(modId: Int, modName: String): Boolean {
        if (modId < 0) return false
        return InstanceManager.listInstances(context).any { meta ->
            meta.sourceModId == modId || (meta.sourceModId == null && meta.name == modName)
        }
    }
}
