package kr.co.donghyun.flamelauncher.presentation.instancesettings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.data.renderer.Renderer
import kr.co.donghyun.flamelauncher.domain.model.InstalledMod
import kr.co.donghyun.flamelauncher.domain.repository.InstanceDetailRepository
import kr.co.donghyun.flamelauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModpackExporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModpackImporter
import javax.inject.Inject

/**
 * InstanceSettingsScreen ViewModel.
 * ⚠️ 이 화면은 도메인 UseCase 계층을 따로 만들지 않고 InstanceDetailRepository 를 직접
 * 주입받는다 — 여러 리포지토리를 조합하는 비즈니스 로직이 없어서(단순 CRUD성 위임)
 * UseCase 를 하나씩 만드는 비용 대비 실익이 낮다고 판단(Google 가이드도 이 경우 선택사항).
 * SAF 피커(ActivityResultLauncher) 자체는 여전히 Activity 몫이라 ViewModel엔 없다.
 *
 * ⚠️ instanceId 는 SavedStateHandle 대신 명시적 initialize() 로 받는다 — 일반
 * ComponentActivity 의 Intent extra 는 SavedStateHandle 에 자동으로 안 들어오기 때문
 * (Nav Graph 인자가 아니라서). Activity 가 onCreated() 에서 딱 한 번 호출한다.
 */
@HiltViewModel
class InstanceSettingsViewModel @Inject constructor(
    private val repository: InstanceDetailRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var instanceId: String = ""
        private set
    var instanceName: String = ""
        private set
    private var initialized = false

    private val _installedMods = MutableStateFlow<List<InstalledMod>>(emptyList())
    val installedMods: StateFlow<List<InstalledMod>> = _installedMods.asStateFlow()

    private val _rendererId = MutableStateFlow<String?>(null)
    val rendererId: StateFlow<String?> = _rendererId.asStateFlow()

    private val _loaderLabel = MutableStateFlow<String?>(null)
    val loaderLabel: StateFlow<String?> = _loaderLabel.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    // 가져오기/내보내기 "완료" 결과 메시지 — 1회성 이벤트(Toast 용).
    // statusMessage(StateFlow) 는 진행 다이얼로그 문구도 겸하다 보니 "완료됐는지"를
    // 문자열로 구분하기 애매해서, 완료 시점에만 별도로 흘려보낸다.
    private val _resultEvents = Channel<String>(Channel.BUFFERED)
    val resultEvents = _resultEvents.receiveAsFlow()

    /** Activity.onCreated() 에서 인텐트 extra 를 받은 직후 딱 한 번 호출. */
    fun initialize(instanceId: String, instanceName: String) {
        if (initialized) return
        initialized = true
        this.instanceId = instanceId
        this.instanceName = instanceName
        _loaderLabel.value = repository.detectLoaderLabel(instanceId)
        _rendererId.value = repository.getRendererId(instanceId)
        refreshMods()
    }

    fun refreshMods() {
        _installedMods.value = repository.getInstalledMods(instanceId)
    }

    fun deleteMod(fileName: String) {
        repository.deleteMod(instanceId, fileName)
        refreshMods()
    }

    fun selectRenderer(id: String?): String {
        repository.setRendererId(instanceId, id)
        _rendererId.value = id
        return id?.let { Renderer.fromId(it).displayName } ?: "전역 기본"
    }

    fun hasExportableMods(): Boolean = repository.hasExportableMods(instanceId)

    fun importMap(zipUri: Uri) {
        _statusMessage.value = "맵을 가져오는 중…"
        _importing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.importMap(instanceId, zipUri)
            _importing.value = false
            _resultEvents.send(when (result) {
                is MapImporter.Result.Success -> "'${result.worldName}' 맵을 가져왔습니다 (${result.fileCount}개 파일)"
                is MapImporter.Result.Failure -> result.reason
            })
        }
    }

    fun importMods(jarUris: List<Uri>) {
        _statusMessage.value = "모드를 추가하는 중…"
        _importing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.importMods(instanceId, jarUris)
            _importing.value = false
            _resultEvents.send(when (result) {
                is ModImporter.Result.Success -> buildString {
                    append("모드 ${result.added.size}개 추가됨")
                    if (result.skipped.isNotEmpty()) append(" · ${result.skipped.size}개 건너뜀")
                }
                is ModImporter.Result.Failure -> result.reason
            })
            refreshMods()
        }
    }

    fun importModpack(zipUri: Uri) {
        _statusMessage.value = "모드팩을 가져오는 중…"
        _importing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.importModpack(instanceId, zipUri)
            _importing.value = false
            _resultEvents.send(when (result) {
                is ModpackImporter.Result.Success -> buildString {
                    append("모드 ${result.modCount}개")
                    if (result.configCount > 0) append(" · 설정 ${result.configCount}개")
                    append(" 가져옴")
                    if (result.failedDownloads > 0) {
                        append("\n⚠️ ${result.failedDownloads}개 모드는 다운로드에 실패했어요(네트워크 상태를 확인하고 다시 시도해 주세요).")
                    }
                    if (result.mcMismatch) {
                        val packMc = result.manifest?.mcVersion ?: "?"
                        append("\n주의: 모드팩 버전($packMc)이 이 인스턴스와 달라 작동하지 않을 수 있어요.")
                    }
                }
                is ModpackImporter.Result.Failure -> result.reason
            })
            refreshMods()
        }
    }

    fun exportModpack(outputUri: Uri) {
        _statusMessage.value = "모드팩으로 추출하는 중…"
        _importing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.exportModpack(instanceId, instanceName, outputUri)
            _importing.value = false
            _resultEvents.send(when (result) {
                is ModpackExporter.Result.Success -> buildString {
                    append("모드 ${result.modCount}개")
                    if (result.configCount > 0) append(" · 설정 ${result.configCount}개")
                    append("를 모드팩으로 추출했습니다.")
                }
                is ModpackExporter.Result.Failure -> result.reason
            })
        }
    }

    fun deleteInstance() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteInstance(instanceId)
            _deleted.value = true
        }
    }
}
