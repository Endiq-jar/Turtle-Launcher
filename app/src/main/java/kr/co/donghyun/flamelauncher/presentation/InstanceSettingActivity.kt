package kr.co.donghyun.flamelauncher.presentation

import kr.co.donghyun.flamelauncher.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.instancesettings.InstanceSettingsViewModel
import kr.co.donghyun.flamelauncher.presentation.ui.screen.InstanceSettingsScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlameLauncherTheme

/**
 * 인스턴스별 설정 화면 — Clean Architecture 마이그레이션 완료.
 * 실질적인 로직(모드 스캔/삭제, 렌더러, 가져오기/내보내기)은 InstanceSettingsViewModel →
 * InstanceDetailRepository 로 옮겼다. Activity 는 다음만 담당하는 얇은 껍데기다:
 *  - Hilt 로 ViewModel 주입
 *  - SAF 파일 피커(ActivityResultLauncher) 등록/실행 — Android API라 ViewModel이 못 함
 *  - 피커가 돌려준 Uri 를 ViewModel 에 넘기기
 */
@AndroidEntryPoint
class InstanceSettingsActivity : BaseActivity() {

    private val viewModel: InstanceSettingsViewModel by viewModels()

    private val mapPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importMap(uri)
    }

    private val modPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importMods(uris)
    }

    private val modpackExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) viewModel.exportModpack(uri)
    }

    private val modpackImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importModpack(uri)
    }

    private val zipMimeTypes = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    // 일부 파일관리자/다운로드 폴더는 .jar 을 비표준 MIME 으로 노출하거나 매칭이 안 돼
    // 피커에서 회색으로 비활성된다(OptiFine 등). "*/*" 를 포함해 모두 보이게 하고,
    // 실제 jar(zip) 여부는 ModImporter 의 PK 매직바이트 검사로 거른다.
    private val jarMimeTypes = arrayOf(
        "application/java-archive",
        "application/x-java-archive",
        "application/octet-stream",
        "*/*",
    )

    override fun onCreated() {
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run { finish(); return }
        val instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME) ?: instanceId
        viewModel.initialize(instanceId, instanceName)

        setContent {
            FlameLauncherTheme {
                val installedMods by viewModel.installedMods.collectAsState()
                val rendererId by viewModel.rendererId.collectAsState()
                val loaderLabel by viewModel.loaderLabel.collectAsState()
                val statusMessage by viewModel.statusMessage.collectAsState()
                val importing by viewModel.importing.collectAsState()
                val deleted by viewModel.deleted.collectAsState()

                InstanceSettingsScreen(
                    instanceName = viewModel.instanceName,
                    loaderInstalled = loaderLabel != null,
                    loaderLabel = loaderLabel,
                    currentRendererId = rendererId,
                    onRendererSelected = { id ->
                        val label = viewModel.selectRenderer(id)
                        Toast.makeText(this, getString(R.string.renderer_set_to, label), Toast.LENGTH_SHORT).show()
                    },
                    installedMods = installedMods,
                    onDeleteMod = { fileName -> viewModel.deleteMod(fileName) },
                    refreshMods = { viewModel.refreshMods() },
                    isImporting = importing,
                    statusMessage = statusMessage,
                    onLaunchMapPicker = { launchMapPicker() },
                    onLaunchModPicker = { launchModPicker() },
                    onImportModpack = { launchModpackImporter() },
                    onExportModpack = { launchModpackExporter() },
                    onDeleteInstance = { viewModel.deleteInstance() },
                    deleted = deleted,
                    finish = { finish() }
                )

                // 가져오기/내보내기 완료 결과를 1회성 이벤트로 받아 Toast 로 표시.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    viewModel.resultEvents.collect { message ->
                        Toast.makeText(this@InstanceSettingsActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun launchMapPicker() {
        try {
            mapPicker.launch(zipMimeTypes)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "맵 피커 실행 실패: ${e.message}", e)
            Toast.makeText(this, getString(R.string.cannot_open_file_picker), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchModPicker() {
        try {
            modPicker.launch(jarMimeTypes)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "모드 피커 실행 실패: ${e.message}", e)
            Toast.makeText(this, getString(R.string.cannot_open_file_picker), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchModpackExporter() {
        if (!viewModel.hasExportableMods()) {
            Toast.makeText(this, getString(R.string.no_mods_to_export), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val safeName = viewModel.instanceName.ifBlank { viewModel.instanceId }
                .replace(Regex("[^a-zA-Z0-9가-힣_\\-]"), "_")
                .take(40)
            modpackExporter.launch("${safeName}-modpack.zip")
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "모드팩 내보내기 피커 실행 실패: ${e.message}", e)
            Toast.makeText(this, getString(R.string.cannot_open_save_location_picker), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchModpackImporter() {
        try {
            modpackImportPicker.launch(zipMimeTypes)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "모드팩 가져오기 피커 실행 실패: ${e.message}", e)
            Toast.makeText(this, getString(R.string.cannot_open_file_picker), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_INSTANCE_NAME = "instance_name"

        fun start(context: Context, instanceId: String, instanceName: String) {
            context.startActivity(
                Intent(context, InstanceSettingsActivity::class.java).apply {
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    putExtra(EXTRA_INSTANCE_NAME, instanceName)
                }
            )
        }
    }
}
