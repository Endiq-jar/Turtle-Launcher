package kr.co.donghyun.flamelauncher.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.domain.model.DownloadPhase
import kr.co.donghyun.flamelauncher.domain.model.DownloadProgress
import kr.co.donghyun.flamelauncher.domain.model.Instance
import kr.co.donghyun.flamelauncher.domain.model.LaunchParams
import kr.co.donghyun.flamelauncher.domain.model.McVersion
import kr.co.donghyun.flamelauncher.domain.model.UserSession
import kr.co.donghyun.flamelauncher.domain.usecase.GetAuthStateUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.GetInstancesUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.GetMcVersionsUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.InstallFabricUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.InstallForgeUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.InstallVanillaUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.PrepareLaunchUseCase
import javax.inject.Inject

/**
 * MainScreen(홈 화면)의 ViewModel. 기존 MainActivity 에 있던 상태·다운로드/설치 로직을
 * 전부 이관했다 — Activity 는 이제 이 ViewModel 을 관찰하고, 실제 Android API가 필요한
 * 부분(ActivityResultLauncher로 다른 화면 열기)만 담당하는 얇은 껍데기가 된다.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val getInstancesUseCase: GetInstancesUseCase,
    private val getMcVersionsUseCase: GetMcVersionsUseCase,
    private val getAuthStateUseCase: GetAuthStateUseCase,
    private val installVanillaUseCase: InstallVanillaUseCase,
    private val installFabricUseCase: InstallFabricUseCase,
    private val installForgeUseCase: InstallForgeUseCase,
    private val prepareLaunchUseCase: PrepareLaunchUseCase,
) : ViewModel() {

    private val _versions = MutableStateFlow<List<McVersion>>(emptyList())
    val versions: StateFlow<List<McVersion>> = _versions.asStateFlow()

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private val _selectedVersion = MutableStateFlow<McVersion?>(null)
    val selectedVersion: StateFlow<McVersion?> = _selectedVersion.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    private val _launchingInstance = MutableStateFlow<Instance?>(null)
    val launchingInstance: StateFlow<Instance?> = _launchingInstance.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError: StateFlow<String?> = _installError.asStateFlow()

    // 설치/실행 준비가 끝나면 여기로 1회성 이벤트를 흘려보낸다.
    // Activity 가 이걸 collect 해서 실제 MinecraftActivity.startForResult(...) 호출한다
    // (다른 Activity를 여는 건 ViewModel이 아니라 Activity의 책임이라 이벤트로 넘긴다).
    private val _launchEvents = Channel<LaunchParams>(Channel.BUFFERED)
    val launchEvents = _launchEvents.receiveAsFlow()

    init {
        refreshLoginState()
        refreshInstances()
        loadVersions()
    }

    private fun loadVersions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _versions.value = getMcVersionsUseCase()
            } catch (e: Exception) {
                _installError.value = "버전 목록 로드 실패: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshInstances() {
        viewModelScope.launch(Dispatchers.IO) {
            _instances.value = getInstancesUseCase()
        }
    }

    fun refreshLoginState() {
        _session.value = getAuthStateUseCase()
    }

    fun selectVersion(version: McVersion?) {
        _selectedVersion.value = version
    }

    fun downloadVanilla(version: McVersion) = launchInstall {
        installVanillaUseCase(version) { _progress.value = it }
    }

    fun downloadFabric(version: McVersion, loaderVersion: String) = launchInstall {
        installFabricUseCase(version, loaderVersion) { _progress.value = it }
    }

    fun downloadForge(version: McVersion, loaderVersion: String, isNeoForge: Boolean) = launchInstall {
        installForgeUseCase(version, loaderVersion, isNeoForge) { _progress.value = it }
    }

    /** 이미 설치된 인스턴스 실행. */
    fun launchInstance(instance: Instance) {
        _launchingInstance.value = instance
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val params = prepareLaunchUseCase(instance)
                _launchEvents.send(params)
            } catch (e: Exception) {
                _launchingInstance.value = null
                _installError.value = "실행 실패: ${e.message}"
            }
        }
    }

    /** MinecraftActivity 등으로 전환되면 실행 팝업을 내린다(Activity의 onPause에서 호출). */
    fun clearLaunchingPopup() {
        _launchingInstance.value = null
    }

    fun clearInstallError() {
        _installError.value = null
    }

    private fun launchInstall(block: suspend () -> LaunchParams) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.FETCHING_MANIFEST
                )
                val params = block()
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.DONE
                )
                _launchEvents.send(params)
            } catch (e: Exception) {
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.ERROR,
                    error = e.message
                )
            }
        }
    }
}
