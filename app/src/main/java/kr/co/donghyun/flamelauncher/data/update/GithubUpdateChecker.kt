package kr.co.donghyun.flamelauncher.data.update

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub 릴리스 기반 자동 업데이트 감지.
 *
 * Endiq-jar/TurtleLauncher 저장소의 최신 릴리스를 조회해, 현재 앱 버전(BuildConfig.VERSION_NAME)
 * 보다 새 버전이 올라오면 [GithubRelease] 를 돌려준다. (프리릴리스/베타도 포함해서 감지)
 */
data class GithubRelease(
    val tagName: String,        // 예: "v2.0.0"
    val name: String,           // 릴리스 제목
    val body: String,           // 릴리스 노트(마크다운)
    val htmlUrl: String,        // 릴리스 페이지 URL
    val apkUrl: String?,        // 첨부된 .apk 다운로드 URL(있으면)
    val prerelease: Boolean,
)

object GithubUpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/Endiq-jar/TurtleLauncher/releases?per_page=10"

    // GitHub API 는 User-Agent 헤더가 없는 요청을 403 으로 거부한다(문서화된 요구사항).
    // ModrinthAPI 와 동일한 컨벤션의 식별 가능한 UA 를 붙인다.
    private val userAgent = "Endiq-jar/TurtleLauncher/${BuildConfig.VERSION_NAME} (kr.co.donghyun.flamelauncher)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 현재 버전보다 새 릴리스가 있으면 반환, 없거나 실패하면 null.
     * @param currentVersion BuildConfig.VERSION_NAME (예: "1.0")
     */
    suspend fun fetchUpdate(currentVersion: String): GithubRelease? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = resp.body?.string() ?: return@withContext null
                val arr = JsonParser.parseString(json).asJsonArray

                // 초안(draft) 제외, 버전 내림차순으로 정렬해 가장 높은 릴리스를 고른다.
                val releases = arr.mapNotNull { el ->
                    val o = el.asJsonObject
                    if (o["draft"]?.asBoolean == true) return@mapNotNull null
                    val tag = o["tag_name"]?.asString ?: return@mapNotNull null
                    val apk = o["assets"]?.asJsonArray
                        ?.map { it.asJsonObject }
                        ?.firstOrNull { it["name"]?.asString?.endsWith(".apk", true) == true }
                        ?.get("browser_download_url")?.asString
                    GithubRelease(
                        tagName = tag,
                        name = o["name"]?.asString?.takeIf { it.isNotBlank() } ?: tag,
                        body = o["body"]?.asString ?: "",
                        htmlUrl = o["html_url"]?.asString ?: "",
                        apkUrl = apk,
                        prerelease = o["prerelease"]?.asBoolean ?: false,
                    )
                }.sortedByDescending { versionKey(it.tagName) }

                val latest = releases.firstOrNull() ?: return@withContext null
                if (isNewer(latest.tagName, currentVersion)) latest else null
            }
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "업데이트 확인 실패: ${e.message}")
            null
        }
    }

    /** latestTag 가 current 보다 높은 버전인지. 접두사 v/V 및 -beta 등 접미사는 무시하고 숫자만 비교. */
    fun isNewer(latestTag: String, current: String): Boolean {
        return compareVersions(latestTag, current) > 0
    }

    /** "v2.0.0-beta1" → 비교 가능한 정수 키(major*1_000_000 + minor*1_000 + patch). 정렬 전용. */
    private fun versionKey(tag: String): Long {
        val p = numbers(tag)
        val major = p.getOrElse(0) { 0 }
        val minor = p.getOrElse(1) { 0 }
        val patch = p.getOrElse(2) { 0 }
        return major * 1_000_000L + minor * 1_000L + patch
    }

    /** 버전 문자열 두 개를 숫자 파트별로 비교. a>b → 양수. */
    private fun compareVersions(a: String, b: String): Int {
        val pa = numbers(a)
        val pb = numbers(b)
        val n = maxOf(pa.size, pb.size)
        for (idx in 0 until n) {
            val x = pa.getOrElse(idx) { 0 }
            val y = pb.getOrElse(idx) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 문자열에서 "1.20.1" 같은 앞부분 숫자 파트만 추출. 첫 숫자 이후 하이픈/문자 만나면 중단. */
    private fun numbers(version: String): List<Int> {
        val core = version.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
        return core.split(".").mapNotNull { it.toIntOrNull() }
    }
}
