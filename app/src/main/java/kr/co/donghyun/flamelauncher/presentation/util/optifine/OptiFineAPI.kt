package kr.co.donghyun.flamelauncher.presentation.util.optifine

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 다이얼로그/설치에 쓰이는 OptiFine 버전 한 개.
 *
 * @param mcVersion     대상 마인크래프트 버전 (= inherit, 예 "1.20.1")
 * @param type          OptiFine 타입 (예 "HD_U")
 * @param patch         패치 식별자 (예 "I6", "J1_pre18")
 * @param fileName      OptiFine installer jar 파일명 (예 "OptiFine_1.20.1_HD_U_I6.jar")
 * @param displayName   표시명 (예 "1.20.1 HD U I6")
 * @param versionName   인스턴스 버전 id (예 "1.20.1-OptiFine_HD_U_I6")
 * @param requiredForge OptiFine 이 요구하는 최소 Forge 버전. null=무관/없음, 빈문자열=무제한
 * @param isPreview     프리뷰(pre) 빌드 여부
 */
data class OptiFineLoaderEntry(
    val mcVersion: String,
    val type: String,
    val patch: String,
    val fileName: String,
    val displayName: String,
    val versionName: String,
    val requiredForge: String?,
    val isPreview: Boolean,
)

/**
 * OptiFine 버전 메타데이터 API.
 *
 * 소스: BMCLAPI ( https://bmclapi2.bangbang93.com/optifine/versionList ).
 *   optifine.net 공식은 adloadx 광고 페이지에서 다운로드 토큰을 HTML 스크래핑해야 해서 불안정한데,
 *   BMCLAPI 는 전체 목록을 깔끔한 JSON 으로 주고, jar 도
 *   https://bmclapi2.bangbang93.com/optifine/{mc}/{type}/{patch} 로 직접 받을 수 있다.
 *   (ZL2 OptiFineVersions.fetchListWithBMCLAPI 와 동일한 응답 모델/구성 로직)
 *
 * 응답 항목(OptiFineVersionToken):
 *   { "mcversion":"1.20.1", "type":"HD_U", "patch":"I6",
 *     "filename":"OptiFine_1.20.1_HD_U_I6.jar", "forge":"Forge 47.0.1" | "N/A" | null }
 */
class OptiFineMetaAPI {

    companion object {
        private const val TAG = "OptiFineMetaAPI"
        private const val VERSION_LIST_URL = "https://bmclapi2.bangbang93.com/optifine/versionList"

        /**
         * 특정 OptiFine 버전의 installer jar 다운로드 URL (BMCLAPI 직링크).
         *
         * ZL2 getDownloadUrlWithBMCLAPI 와 동일한 규칙으로 구성한다:
         *   - inherit(=mcVersion)이 1.8 / 1.9 면 1.8.0 / 1.9.0 으로 보정
         *   - displayName 에서 "{mc} " 접두사를 떼어 patch 부분(displayNameStripped)을 얻는다
         *     (예 displayName="1.20.1 HD U I6" → "HD U I6")  ※ ZL2 nameDisplay 는 HD_U 를 지우므로
         *       실제로는 "1.20.1 I6" → strip → "I6" 형태가 일반적
         *   - 정식:   .../optifine/{inherit}/HD_U/{displayNameStripped}
         *   - 프리뷰: .../optifine/{inherit}/HD_U_{displayNameStripped(공백→/)}
         */
        fun downloadUrl(entry: OptiFineLoaderEntry): String {
            val inherit = if (entry.mcVersion == "1.8" || entry.mcVersion == "1.9")
                "${entry.mcVersion}.0" else entry.mcVersion
            val stripped = entry.displayName.removePrefix("${entry.mcVersion} ").trim()
            val suffix = if (entry.isPreview) {
                "HD_U_${stripped.replace(" ", "/")}"
            } else {
                "HD_U/$stripped"
            }
            return "https://bmclapi2.bangbang93.com/optifine/$inherit/$suffix"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 주어진 마인크래프트 버전에 사용 가능한 OptiFine 목록을 반환한다.
     * 최신 patch 가 위로 오도록 정렬한다(프리뷰는 뒤로).
     *
     * @throws Exception 네트워크/파싱 실패 시
     */
    fun listLoaders(mcVersion: String): List<OptiFineLoaderEntry> {
        val req = Request.Builder().url(VERSION_LIST_URL).build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("OptiFine 목록 HTTP ${resp.code}")
            }
            resp.body?.string() ?: throw IllegalStateException("OptiFine 목록 응답 비어있음")
        }

        val arr = JsonParser.parseString(body).asJsonArray
        val all = arr.mapNotNull { el ->
            val o = el.asJsonObject
            val mc = o["mcversion"]?.asString ?: return@mapNotNull null
            val type = o["type"]?.asString ?: "HD_U"
            val patch = o["patch"]?.asString ?: return@mapNotNull null
            val fileName = o["filename"]?.asString
                ?: "OptiFine_${mc}_${type}_$patch.jar"
            val forgeRaw = o["forge"]?.takeIf { !it.isJsonNull }?.asString

            // 표시명: ZL2 와 동일하게 "HD_U" 를 지우고 언더스코어를 공백으로.
            //   "1.20.1" + " I6"  → "1.20.1 HD U I6" 형태에서 HD_U 제거 → "1.20.1 I6"
            val nameDisplay = (mc + type.replace("HD_U", "").replace("_", " ") + " " + patch)
                .replace(".0 ", " ")
                .trim()

            // 요구 Forge 버전: "Forge 47.0.1" → "47.0.1", "#"/"N/A" 정리
            val requiredForge = forgeRaw
                ?.replace("Forge ", "")
                ?.replace("#", "")
                ?.takeUnless { it.contains("N/A", ignoreCase = true) }
                ?.trim()

            // 인스턴스 버전 id: "1.20.1-OptiFine_HD_U_I6"
            val versionName = mc + "-OptiFine_" +
                    (type + " " + patch)
                        .replace(".0 ", " ")
                        .replace(" ", "_")
                        .replace(mc + "_", "")

            OptiFineLoaderEntry(
                mcVersion = mc,
                type = type,
                patch = patch,
                fileName = fileName,
                displayName = nameDisplay,
                versionName = versionName,
                requiredForge = requiredForge,
                isPreview = patch.contains("pre", ignoreCase = true),
            )
        }

        // 해당 MC 버전만, 프리뷰는 뒤로 + patch 내림차순(대략 최신이 위).
        return all
            .filter { it.mcVersion == mcVersion }
            .sortedWith(
                compareBy<OptiFineLoaderEntry> { it.isPreview }
                    .thenByDescending { it.patch }
            )
            .also { Log.i(TAG, "OptiFine($mcVersion) ${it.size}개") }
    }
}