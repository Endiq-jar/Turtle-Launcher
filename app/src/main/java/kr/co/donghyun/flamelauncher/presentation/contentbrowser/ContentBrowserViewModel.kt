package kr.co.donghyun.flamelauncher.presentation.contentbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.data.mods.ContentItem
import kr.co.donghyun.flamelauncher.data.mods.ContentSource
import kr.co.donghyun.flamelauncher.data.repository.ContentInstallRepositoryImpl
import kr.co.donghyun.flamelauncher.domain.model.LaunchParams
import kr.co.donghyun.flamelauncher.domain.repository.ContentRepository
import kr.co.donghyun.flamelauncher.presentation.ModLoader
import kr.co.donghyun.flamelauncher.presentation.ui.screen.ContentType
import javax.inject.Inject

/**
 * ContentPackBrowserScreen ViewModel.
 * ⚠️ 설치 로직(installDirect/installModrinthModpack/installToExistingInstance/
 * installToNewInstance/launchMod)은 ContentInstallRepositoryImpl 이 그대로 갖고 있고,
 * 여기서는 그 진행상태(progress/isInstalling/statusMessage/errorDialog)를 그대로
 * re-expose 하고 호출만 위임한다. 실제 MinecraftActivity 로 전환은 Activity가 담당
 * 해야 해서(ApplicationContext로 startActivity 하면 FLAG_ACTIVITY_NEW_TASK 없이 크래시),
 * launchMod/설치 완료 시 LaunchParams 를 1회성 이벤트로 흘려보낸다(MainViewModel과 동일 패턴).
 */
@HiltViewModel
class ContentBrowserViewModel @Inject constructor(
    private val repository: ContentRepository,
    private val installRepository: ContentInstallRepositoryImpl,
) : ViewModel() {

    private val _contentPacks = MutableStateFlow<List<ContentItem>>(emptyList())
    val contentPacks: StateFlow<List<ContentItem>> = _contentPacks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedSource = MutableStateFlow(ContentSource.CURSEFORGE)
    val selectedSource: StateFlow<ContentSource> = _selectedSource.asStateFlow()

    private val _selectedContentType = MutableStateFlow(ContentType.MODPACK)
    val selectedContentType: StateFlow<ContentType> = _selectedContentType.asStateFlow()

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _selectedMcVersion = MutableStateFlow("")
    val selectedMcVersion: StateFlow<String> = _selectedMcVersion.asStateFlow()

    private val _selectedLoaderFilter = MutableStateFlow("")
    val selectedLoaderFilter: StateFlow<String> = _selectedLoaderFilter.asStateFlow()

    private val _availableMcVersions = MutableStateFlow<List<String>>(emptyList())
    val availableMcVersions: StateFlow<List<String>> = _availableMcVersions.asStateFlow()

    // ── 설치 진행상태: ContentInstallRepositoryImpl 을 그대로 re-expose ──
    //   ContentPackBrowserScreen(UI)이 data.mojang.DownloadProgress 를 그대로 쓰고 있어서
    //   (이번 라운드에서 Screen 시그니처는 안 건드림) 별도 domain 타입 매핑 없이 그대로 넘긴다.
    val progress: StateFlow<kr.co.donghyun.flamelauncher.data.mojang.DownloadProgress> = installRepository.progress
    val isInstalling: StateFlow<Boolean> = installRepository.isInstalling
    val installingModId: StateFlow<String?> = installRepository.installingModId
    val installStatusMessage: StateFlow<String> = installRepository.statusMessage
    val errorDialogMessage: StateFlow<String?> = installRepository.errorDialogMessage

    fun dismissErrorDialog() = installRepository.clearErrorDialog()

    // 설치/실행 준비가 끝나면 1회성 이벤트로 LaunchParams 를 흘려보낸다.
    private val _launchEvents = Channel<LaunchParams>(Channel.BUFFERED)
    val launchEvents = _launchEvents.receiveAsFlow()

    private var currentQuery: String = ""
    private var currentPageIndex: Int = 0
    private val pageSize: Int = 20
    private var searchJob: Job? = null

    init {
        debouncedSearch("", ContentType.MODPACK)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _availableMcVersions.value = repository.getAvailableMcVersions()
            } catch (_: Exception) {
                // 필터 비활성으로 조용히 무시(기존 동작 그대로)
            }
        }
    }

    fun debouncedSearch(query: String, type: ContentType) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            currentQuery = query
            _selectedContentType.value = type
            currentPageIndex = 0
            _contentPacks.value = emptyList()
            _hasMore.value = true
            performSearch(reset = true)
        }
    }

    fun changeSource(source: ContentSource) {
        if (_selectedSource.value == source) return
        _selectedSource.value = source
        debouncedSearch(currentQuery, _selectedContentType.value)
    }

    fun changeContentType(type: ContentType) {
        _selectedContentType.value = type
    }

    fun changeMcVersionFilter(version: String) {
        if (_selectedMcVersion.value == version) return
        _selectedMcVersion.value = version
        debouncedSearch(currentQuery, _selectedContentType.value)
    }

    fun changeLoaderFilter(loader: String) {
        if (_selectedLoaderFilter.value == loader) return
        _selectedLoaderFilter.value = loader
        debouncedSearch(currentQuery, _selectedContentType.value)
    }

    fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return
        viewModelScope.launch { performSearch(reset = false) }
    }

    fun refreshInstalledIds() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedIds.value = repository.getInstalledContentKeys(_contentPacks.value)
        }
    }

    // ── 설치 트리거: ContentInstallRepositoryImpl 로 위임, 끝나면 installedIds 갱신 ──

    fun installDirect(mod: ContentItem, contentType: ContentType, fileId: Int?, mrVersionId: String?) {
        viewModelScope.launch {
            installRepository.installDirect(mod, contentType, fileId, mrVersionId)
            refreshInstalledIds()
        }
    }

    fun installModrinthModpack(mod: ContentItem, mrVersionId: String? = null) {
        viewModelScope.launch {
            installRepository.installModrinthModpack(mod, mrVersionId)
            refreshInstalledIds()
        }
    }

    fun installToExistingInstance(mod: ContentItem, instanceId: String, contentType: ContentType, worldName: String?) {
        viewModelScope.launch {
            installRepository.installToExistingInstance(mod, instanceId, contentType, worldName)
            refreshInstalledIds()
        }
    }

    fun installToNewInstance(mod: ContentItem, version: String, loader: ModLoader?, contentType: ContentType, newToken: String?) {
        viewModelScope.launch {
            installRepository.installToNewInstance(mod, version, loader, contentType, newToken)
            refreshInstalledIds()
        }
    }

    fun launchMod(mod: ContentItem) {
        viewModelScope.launch {
            val params = installRepository.launchMod(mod)
            if (params != null) _launchEvents.send(params)
        }
    }

    private suspend fun performSearch(reset: Boolean) {
        _isLoading.value = true
        try {
            val source = _selectedSource.value
            val mcVersionFilter = _selectedMcVersion.value
            val loaderFilter = _selectedLoaderFilter.value
            val results: List<ContentItem> = withContext(Dispatchers.IO) {
                when (source) {
                    ContentSource.CURSEFORGE ->
                        repository.searchCurseForge(
                            query = currentQuery,
                            classId = _selectedContentType.value.classId,
                            gameVersion = mcVersionFilter,
                            modLoaderType = curseForgeLoaderTypeId(loaderFilter),
                            index = currentPageIndex,
                            pageSize = pageSize,
                        )
                    ContentSource.MODRINTH ->
                        repository.searchModrinth(
                            query = currentQuery,
                            projectType = _selectedContentType.value.modrinthType,
                            gameVersion = mcVersionFilter,
                            loader = loaderFilter,
                            offset = currentPageIndex,
                            limit = pageSize,
                        )
                }
            }
            val merged = if (reset) results else _contentPacks.value + results
            _contentPacks.value = merged.distinctBy { it.trackKey }
            currentPageIndex += results.size
            _hasMore.value = results.size >= pageSize
            _searchError.value = null
            refreshInstalledIds()
        } catch (e: Exception) {
            _hasMore.value = false
            _searchError.value = "${e.javaClass.simpleName}: ${e.message ?: "알 수 없는 오류"}"
        } finally {
            _isLoading.value = false
        }
    }

    /** "fabric"/"forge"/"neoforge"/"quilt" → CurseForge modLoaderType 숫자값. */
    private fun curseForgeLoaderTypeId(loader: String): Int? = when (loader.lowercase()) {
        "forge" -> 1
        "fabric" -> 4
        "quilt" -> 5
        "neoforge" -> 6
        else -> null
    }
}
