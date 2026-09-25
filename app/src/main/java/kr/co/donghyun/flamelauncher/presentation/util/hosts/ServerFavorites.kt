package kr.co.donghyun.flamelauncher.presentation.util.hosts

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.jvm.isLegacyVersion
import java.io.File

/**
 * "내 서버" 즐겨찾기.
 *
 * 사용 시나리오(친구가 PC + playit.gg 로 호스트한 월드에 모바일로 접속):
 *   1) 친구가 준 playit 주소(예: purple-cat-1234.playit.gg:25565)를 인스턴스에 등록
 *   2) 등록 시 그 인스턴스의 servers.dat 에 자동 주입 → 게임 멀티플레이 목록에 노출
 *   3) 게임 실행 → 멀티플레이 목록에서 탭 → 접속
 *
 * 즐겨찾기는 **인스턴스별**로 관리한다(playit 주소는 보통 특정 MC 버전 월드용).
 * 저장: filesDir/server_favorites/<instanceId>.json
 */
object ServerFavorites {

    private val gson = Gson()

    data class Favorite(
        val name: String,
        val address: String,   // host 또는 host:port
    )

    private fun favFile(context: Context, instanceId: String): File {
        val dir = File(context.filesDir, "server_favorites").also { it.mkdirs() }
        return File(dir, "$instanceId.json")
    }

    /** 인스턴스의 즐겨찾기 목록 로드. */
    fun list(context: Context, instanceId: String): List<Favorite> {
        val f = favFile(context, instanceId)
        if (!f.isFile) return emptyList()
        return try {
            val type = object : TypeToken<List<Favorite>>() {}.type
            gson.fromJson<List<Favorite>>(f.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "즐겨찾기 로드 실패: ${e.message}")
            emptyList()
        }
    }

    private fun save(context: Context, instanceId: String, favs: List<Favorite>) {
        try {
            favFile(context, instanceId).writeText(gson.toJson(favs))
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "즐겨찾기 저장 실패: ${e.message}")
        }
    }

    /**
     * 즐겨찾기 추가(또는 같은 주소면 이름 갱신) + servers.dat 주입.
     * @return 성공 여부
     */
    fun add(context: Context, instanceId: String, mcVersion: String, name: String, address: String): Boolean {
        val cleanName = name.trim().ifBlank { address.trim() }
        val cleanAddr = address.trim()
        if (cleanAddr.isEmpty()) return false

        // 1) 런처 JSON 갱신
        val favs = list(context, instanceId).toMutableList()
        val idx = favs.indexOfFirst { it.address.equals(cleanAddr, ignoreCase = true) }
        if (idx >= 0) favs[idx] = Favorite(cleanName, cleanAddr) else favs.add(Favorite(cleanName, cleanAddr))
        save(context, instanceId, favs)

        // 2) servers.dat 주입 (게임 멀티 목록에 노출)
        return try {
            val instanceDir = InstanceManager.instanceDir(context, instanceId)
            val datFile = ServersDat.serversDatFile(instanceDir, isLegacyVersion(mcVersion))
            ServersDat.upsert(datFile, ServersDat.ServerEntry(name = cleanName, ip = cleanAddr))
            true
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "servers.dat 주입 실패: ${e.message}")
            false
        }
    }

    /** 즐겨찾기 삭제 + servers.dat 에서도 제거. */
    fun remove(context: Context, instanceId: String, mcVersion: String, address: String) {
        val favs = list(context, instanceId).toMutableList()
        favs.removeAll { it.address.equals(address, ignoreCase = true) }
        save(context, instanceId, favs)

        try {
            val instanceDir = InstanceManager.instanceDir(context, instanceId)
            val datFile = ServersDat.serversDatFile(instanceDir, isLegacyVersion(mcVersion))
            ServersDat.removeByIp(datFile, address)
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "servers.dat 제거 실패: ${e.message}")
        }
    }

    /**
     * 런처 즐겨찾기를 servers.dat 에 일괄 재주입(동기화).
     * 게임 실행 직전에 호출하면, JSON 즐겨찾기가 항상 멀티 목록에 반영된다.
     */
    fun syncToServersDat(context: Context, instanceId: String, mcVersion: String) {
        val favs = list(context, instanceId)
        if (favs.isEmpty()) return
        try {
            val instanceDir = InstanceManager.instanceDir(context, instanceId)
            val datFile = ServersDat.serversDatFile(instanceDir, isLegacyVersion(mcVersion))
            favs.forEach { fav ->
                ServersDat.upsert(datFile, ServersDat.ServerEntry(name = fav.name, ip = fav.address))
            }
            Log.d("FLAME_LAUNCHER", "즐겨찾기 ${favs.size}개를 servers.dat 에 동기화")
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "servers.dat 동기화 실패: ${e.message}")
        }
    }
}