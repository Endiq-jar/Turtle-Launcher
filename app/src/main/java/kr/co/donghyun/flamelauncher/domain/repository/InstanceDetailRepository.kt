package kr.co.donghyun.flamelauncher.domain.repository

import android.net.Uri
import kr.co.donghyun.flamelauncher.domain.model.InstalledMod
import kr.co.donghyun.flamelauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModpackExporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModpackImporter

/**
 * 인스턴스 상세 화면(InstanceSettingsActivity) 계약.
 *
 * ⚠️ Uri 및 ModImporter/MapImporter/ModpackImporter/ModpackExporter 의 Result 타입을
 * 그대로 노출한다 — 이 타입들은 이미 Android 의존성이 없는 순수 값 타입이라, domain 전용
 * 타입으로 한 번 더 복제하는 비용 대비 실익이 낮다고 판단한 의도적 스코프 결정이다
 * (SAF 피커 자체는 Activity 에 남아있고, 여기는 실제 가져오기/내보내기 로직만 담당).
 */
interface InstanceDetailRepository {
    fun getInstalledMods(instanceId: String): List<InstalledMod>
    fun deleteMod(instanceId: String, fileName: String)
    fun detectLoaderLabel(instanceId: String): String?
    fun getRendererId(instanceId: String): String?
    fun setRendererId(instanceId: String, rendererId: String?)
    suspend fun deleteInstance(instanceId: String)

    suspend fun importMap(instanceId: String, zipUri: Uri): MapImporter.Result
    suspend fun importMods(instanceId: String, jarUris: List<Uri>): ModImporter.Result
    suspend fun importModpack(instanceId: String, zipUri: Uri): ModpackImporter.Result
    suspend fun exportModpack(instanceId: String, displayName: String, outputUri: Uri): ModpackExporter.Result
    fun hasExportableMods(instanceId: String): Boolean
}
