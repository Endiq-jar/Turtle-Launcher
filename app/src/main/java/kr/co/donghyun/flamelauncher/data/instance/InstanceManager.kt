package kr.co.donghyun.flamelauncher.data.instance

import android.content.Context
import com.google.gson.Gson
import java.io.File

enum class InstanceType { VANILLA, MODPACK, FABRIC }

data class InstanceMeta(
    val id: String,
    val name: String,
    val type: InstanceType,
    val mcVersion: String,
    val loaderType: String? = null,        // "fabric", "forge"
    val loaderVersion: String? = null,
    val mainClass: String = "net.minecraft.client.main.Main",
    val extraJars: List<String> = emptyList(),
    val assetIndexId: String = "",
    val iconEmoji: String = "🌿",
    /**
     * 다운로드한 인스턴스 아이콘 파일의 절대경로(예: <instanceDir>/icon.png).
     * 모드팩/모드를 CurseForge·Modrinth 에서 설치할 때 그 콘텐츠 로고를 받아 저장한다.
     * null 이면 로더 기본 아이콘(또는 CurseForge 기본 아이콘)으로 폴백 — 하위호환(기존 json엔 키 없음).
     */
    val iconPath: String? = null,
    val gameJvmArgs: List<String> = emptyList(), // Fabric profile의 arguments.jvm
    val gameArgs: List<String> = emptyList(),    // Fabric profile의 arguments.game
    val sourceModId: Int? = null,
    /**
     * 이 인스턴스에 사용할 렌더러 id ("zink" / "gl4es" / "mobileglues").
     * null 이면 전역 기본 렌더러(RendererManager.load)를 따른다 — 하위호환(기존 인스턴스 json엔 키 없음).
     */
    val rendererId: String? = null,
)

object InstanceManager {
    private const val META_FILE = "instance.json"
    private val gson = Gson()

    fun instancesDir(context: Context): File =
        File(context.getExternalFilesDir(null), "instances")

    fun instanceDir(context: Context, id: String): File =
        File(instancesDir(context), id)

    fun listInstances(context: Context): List<InstanceMeta> {
        val dir = instancesDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { loadMeta(it) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun loadMeta(instanceDir: File): InstanceMeta? {
        return try {
            val f = File(instanceDir, META_FILE)
            if (!f.exists()) return null
            gson.fromJson(f.readText(), InstanceMeta::class.java)
        } catch (_: Exception) { null }
    }

    fun saveMeta(context: Context, meta: InstanceMeta) {
        val dir = instanceDir(context, meta.id).also { it.mkdirs() }
        File(dir, META_FILE).writeText(gson.toJson(meta))
    }

    fun deleteInstance(context: Context, id: String) {
        instanceDir(context, id).deleteRecursively()
    }

    // ── 인스턴스별 렌더러 ───────────────────────────────────────────

    /**
     * 이 인스턴스에 설정된 렌더러 id 를 읽는다. 설정 안 했으면 null.
     * (null 이면 호출 측에서 전역 기본 렌더러로 폴백)
     */
    fun loadRendererId(context: Context, id: String): String? =
        loadMeta(instanceDir(context, id))?.rendererId

    /**
     * 이 인스턴스의 렌더러 id 를 갱신·저장한다. 메타가 없으면 무시(false).
     * @param rendererId "zink"/"gl4es"/"mobileglues", 또는 null(전역 기본 사용)
     * @return 저장 성공 여부
     */
    fun updateRendererId(context: Context, id: String, rendererId: String?): Boolean {
        val meta = loadMeta(instanceDir(context, id)) ?: return false
        saveMeta(context, meta.copy(rendererId = rendererId))
        return true
    }

    fun vanillaId(versionId: String) = "vanilla_$versionId"

    fun modpackId(modName: String): String =
        "modpack_" + modName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)

    fun fabricId(mcVersion: String, loaderVersion: String): String =
        "fabric_${mcVersion}_" + loaderVersion.replace(".", "_")

    // ── 신규(고유) 인스턴스용 토큰/접미사 ─────────────────────────────

    /**
     * "새로 설치"를 누를 때마다 같은 MC 버전·로더라도 별개 인스턴스로 분리하기 위한 짧은 토큰.
     * UUID 앞 8자리만 쓴다(충돌 확률 무시 가능, 폴더명도 짧게 유지).
     */
    fun newInstanceToken(): String =
        java.util.UUID.randomUUID().toString().substring(0, 8)

    /**
     * 기존 deterministic id 에 토큰을 덧붙여 고유 id 를 만든다.
     * token 이 null/blank 면 원래 id 를 그대로 반환(=기존 동작/재사용 유지).
     *   예) "vanilla_1.21.4" + "a1b2c3d4" → "vanilla_1.21.4_a1b2c3d4"
     */
    fun withToken(baseId: String, token: String?): String =
        if (token.isNullOrBlank()) baseId else "${baseId}_$token"
}