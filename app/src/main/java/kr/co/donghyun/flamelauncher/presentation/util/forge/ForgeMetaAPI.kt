package kr.co.donghyun.flamelauncher.presentation.util.forge

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 한 Forge 빌드 엔트리.
 *
 *  - fullVersion        : "1.21.1-52.0.10" 같은 정식 풀 표기
 *  - installerUrl       : 호출자가 다운받을 installer JAR URL
 *  - clientUrl          : universal/client JAR URL (필요 시 사용)
 */
data class ForgeLoaderEntry(
    val mcVersion: String,
    val forgeVersion: String,
    val recommended: Boolean,
    val latest: Boolean,
) {
    val fullVersion: String get() = "$mcVersion-$forgeVersion"

    val installerUrl: String get() =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                "$fullVersion/forge-$fullVersion-installer.jar"

    val clientUrl: String get() =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                "$fullVersion/forge-$fullVersion-universal.jar"
}

class ForgeMetaAPI {

    companion object {
        private const val PROMOTIONS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        private const val MAVEN_METADATA_URL =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"

        /**
         * JDK-8032636 회피용 "최소 안전 Forge 빌드".
         *
         * Java 8u20 부터 List.sort()/Collections.sort() 가 modCount 를 올리도록 바뀌었다.
         * 그 이전에 빌드된 구버전 FML 의 CoreModManager.sortTweakList() 는
         * LaunchWrapper 가 순회(iterator) 중인 blackboard "Tweaks" 리스트를
         * Collections.sort() 로 정렬해 버린다. → modCount 가 올라가고,
         * 바로 다음 줄의 Iterator.remove() 가 ConcurrentModificationException 을 던져
         * net.minecraft.launchwrapper.Launch.launch() 단계에서 게임이 아예 안 뜬다.
         * (모드 로드 이전, 라이브러리 단계라 JVM 인자나 인게임 모드로는 못 고친다.)
         *
         * Forge 는 이 문제를 정렬을 toArray() + List.set() 으로 우회하도록 고쳤다
         * (CoreModManager 소스에 JDK 버그번호 8032636 코멘트가 그대로 박혀 있음).
         * 아래 빌드 번호 이상은 모두 이 fix 가 포함되어 있으므로,
         * 해당 MC 버전은 이 미만 빌드를 목록에서 아예 제외해
         * 사용자가 깨진 빌드를 고르지 못하게 한다.
         *
         * 값은 forgeVersion 의 마지막 '.' 세그먼트 = 빌드 번호 기준.
         *   예) "10.13.4.1558" → 1558
         *
         * 1.7.10 의 recommended(1558) / latest(1614) 둘 다 fix 포함이라 그대로 통과한다.
         * (1.6.4 / 1.7.2 등 다른 launchwrapper 시대 버전도 필요하면 여기에 추가하면 됨.)
         */
        private val MIN_SAFE_FORGE_BUILD: Map<String, Int> = mapOf(
            "1.7.10" to 1558,
        )
    }

    /**
     * 특정 마인크래프트 버전에 대해 사용 가능한 모든 Forge 빌드를 반환한다.
     * 정렬: 신 → 구.
     *
     * MIN_SAFE_FORGE_BUILD 에 등록된 버전은 CME 유발 가능 구버전 빌드를 제외하고 반환한다.
     *
     * 네트워크 호출이므로 반드시 IO 디스패처에서 부를 것.
     */
    fun listLoaders(mcVersion: String): List<ForgeLoaderEntry> {
        val promos = fetchPromotions()
        val recommended = promos["$mcVersion-recommended"]
        val latest = promos["$mcVersion-latest"]

        val minSafeBuild = MIN_SAFE_FORGE_BUILD[mcVersion]   // null 이면 필터 안 함

        return fetchAllVersions()
            .asSequence()
            .filter { (mc, _) -> mc == mcVersion }
            // ── JDK-8032636: 구버전 FML 의 CME 유발 빌드 제외 ─────────────────
            .filter { (_, forge) -> isSafeForgeBuild(mcVersion, forge, minSafeBuild) }
            .map { (mc, forge) ->
                ForgeLoaderEntry(
                    mcVersion = mc,
                    forgeVersion = forge,
                    recommended = forge == recommended,
                    latest = forge == latest,
                )
            }
            .toList()
            // Maven metadata 는 일반적으로 오래된 순서. 신 -> 구로 뒤집어 반환.
            .asReversed()
    }

    /**
     * 이 forge 빌드가 (해당 MC 의) 안전 임계값 이상인지.
     * 임계값이 없으면(=등록 안 된 MC) 항상 true.
     * 빌드 번호 파싱이 실패하는 비정상 표기는 막지 않고 통과시키되 로그만 남긴다.
     */
    private fun isSafeForgeBuild(mcVersion: String, forge: String, minSafeBuild: Int?): Boolean {
        if (minSafeBuild == null) return true
        val build = forgeBuildNumber(forge)
        if (build == null) {
            Log.w("FLAME_LAUNCHER", "Forge 빌드 번호 파싱 실패: $forge → 필터 통과")
            return true
        }
        val ok = build >= minSafeBuild
        if (!ok) {
            Log.d("FLAME_LAUNCHER",
                "🚫 $mcVersion-$forge 제외 (JDK-8032636 CME 위험, 최소 안전 빌드 $minSafeBuild)")
        }
        return ok
    }

    /** "10.13.4.1558" → 1558. 마지막 '.' 세그먼트를 빌드 번호로 본다. 실패 시 null. */
    private fun forgeBuildNumber(forgeVersion: String): Int? =
        forgeVersion.substringAfterLast('.').toIntOrNull()

    /** promotions_slim.json 파싱. 실패 시 빈 맵. */
    private fun fetchPromotions(): Map<String, String> = try {
        val text = httpGet(PROMOTIONS_URL)
        val promos = JSONObject(text).getJSONObject("promos")
        promos.keys().asSequence().associateWith { promos.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    /** maven-metadata.xml 파싱 → (mcVersion, forgeVersion) 쌍 목록 */
    private fun fetchAllVersions(): List<Pair<String, String>> {
        val xml = httpGet(MAVEN_METADATA_URL)
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("version")
        val out = ArrayList<Pair<String, String>>(nodes.length)
        for (i in 0 until nodes.length) {
            val v = nodes.item(i).textContent ?: continue
            // "<mcVersion>-<forgeVersion>"  예) "1.21.1-52.0.10"
            val dash = v.indexOf('-')
            if (dash <= 0) continue
            out += v.substring(0, dash) to v.substring(dash + 1)
        }
        return out
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}