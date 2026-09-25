package kr.co.donghyun.flamelauncher.presentation.contentdetail

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
import kr.co.donghyun.flamelauncher.data.mods.ContentSource
import kr.co.donghyun.flamelauncher.data.mods.CurseForgeFile
import kr.co.donghyun.flamelauncher.data.mods.ModrinthVersion
import kr.co.donghyun.flamelauncher.data.repository.ContentDetailRepositoryImpl
import kr.co.donghyun.flamelauncher.domain.model.MinecraftSupport
import kr.co.donghyun.flamelauncher.presentation.ContentDetail
import kr.co.donghyun.flamelauncher.presentation.InstanceSummary
import kr.co.donghyun.flamelauncher.presentation.ModLoader
import kr.co.donghyun.flamelauncher.presentation.VersionLoaderCombo
import kr.co.donghyun.flamelauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModrinthAPI
import javax.inject.Inject

/** ContentPackDetailActivity 가 설치 요청을 마치면 이 결과로 setResult+finish 를 대신 처리한다. */
sealed interface DetailResult {
    data class Install(
        val modId: Int,
        val modKey: String,
        val targetInstanceId: String? = null,
        val targetVersion: String? = null,
        val targetLoader: ModLoader? = null,
        val targetWorld: String? = null,
        val targetFileId: Int? = null,
        val targetMrVersionId: String? = null,
        val targetNewToken: String? = null,
    ) : DetailResult

    data class Launch(val modId: Int, val modKey: String) : DetailResult
}

/**
 * ContentPackDetailScreen ViewModel. 상세조회/버전-로더 감지는
 * ContentDetailRepositoryImpl 에 위임하고, 여기서는 다이얼로그 플로우 상태와
 * "무엇을 설치할지 확정됐을 때" 1회성 결과 이벤트(DetailResult)를 담당한다.
 * 실제 setResult()+finish() 호출은 Activity 몫(Android API라 ViewModel이 못 함).
 */
@HiltViewModel
class ContentDetailViewModel @Inject constructor(
    private val repository: ContentDetailRepositoryImpl,
) : ViewModel() {

    private val _detail = MutableStateFlow<ContentDetail?>(null)
    val detail: StateFlow<ContentDetail?> = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isInstalled = MutableStateFlow(false)
    val isInstalled: StateFlow<Boolean> = _isInstalled.asStateFlow()

    private val _fullscreenIndex = MutableStateFlow<Int?>(null)
    val fullscreenIndex: StateFlow<Int?> = _fullscreenIndex.asStateFlow()

    private val _showInstallTargetDialog = MutableStateFlow(false)
    val showInstallTargetDialog: StateFlow<Boolean> = _showInstallTargetDialog.asStateFlow()

    private val _loaderInstances = MutableStateFlow<List<InstanceSummary>>(emptyList())
    val loaderInstances: StateFlow<List<InstanceSummary>> = _loaderInstances.asStateFlow()

    private val _supportedLoaders = MutableStateFlow<Set<ModLoader>>(emptySet())
    val supportedLoaders: StateFlow<Set<ModLoader>> = _supportedLoaders.asStateFlow()

    private val _supportedMcVersions = MutableStateFlow<Set<String>>(emptySet())
    val supportedMcVersions: StateFlow<Set<String>> = _supportedMcVersions.asStateFlow()

    private val _supportedCombos = MutableStateFlow<List<VersionLoaderCombo>>(emptyList())
    val supportedCombos: StateFlow<List<VersionLoaderCombo>> = _supportedCombos.asStateFlow()

    private val _showWorldDialog = MutableStateFlow(false)
    val showWorldDialog: StateFlow<Boolean> = _showWorldDialog.asStateFlow()

    private val _worldCandidates = MutableStateFlow<List<String>>(emptyList())
    val worldCandidates: StateFlow<List<String>> = _worldCandidates.asStateFlow()

    private var pendingDatapackInstance: InstanceSummary? = null

    private val _showVersionDialog = MutableStateFlow(false)
    val showVersionDialog: StateFlow<Boolean> = _showVersionDialog.asStateFlow()

    private val _versionFiles = MutableStateFlow<List<CurseForgeFile>>(emptyList())
    val versionFiles: StateFlow<List<CurseForgeFile>> = _versionFiles.asStateFlow()

    private val _versionLoading = MutableStateFlow(false)
    val versionLoading: StateFlow<Boolean> = _versionLoading.asStateFlow()

    private val _showMrVersionDialog = MutableStateFlow(false)
    val showMrVersionDialog: StateFlow<Boolean> = _showMrVersionDialog.asStateFlow()

    private val _mrVersionList = MutableStateFlow<List<ModrinthVersion>>(emptyList())
    val mrVersionList: StateFlow<List<ModrinthVersion>> = _mrVersionList.asStateFlow()

    private val _mrVersionLoading = MutableStateFlow(false)
    val mrVersionLoading: StateFlow<Boolean> = _mrVersionLoading.asStateFlow()

    private val _resultEvents = Channel<DetailResult>(Channel.BUFFERED)
    val resultEvents = _resultEvents.receiveAsFlow()

    // 초기화 시 받은 값들 — 다이얼로그 콜백에서 결과 조립할 때 재사용.
    private var modId: Int = -1
    private var modKey: String = ""
    private var modSource: ContentSource = ContentSource.CURSEFORGE
    private var modStringId: String = ""
    private var modName: String = ""
    private var contentType: ContentType = ContentType.MODPACK
    private var initialized = false

    fun initialize(
        modId: Int,
        modKey: String,
        modSource: ContentSource,
        modStringId: String,
        modName: String,
        contentType: ContentType,
    ) {
        if (initialized) return
        initialized = true
        this.modId = modId
        this.modKey = modKey
        this.modSource = modSource
        this.modStringId = modStringId
        this.modName = modName
        this.contentType = contentType

        _isInstalled.value = repository.isContentInstalled(modId, modName)

        val canLoadDetail = when (modSource) {
            ContentSource.CURSEFORGE -> modId != -1
            ContentSource.MODRINTH -> modStringId.isNotBlank()
        }
        if (canLoadDetail) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    _detail.value = when (modSource) {
                        ContentSource.CURSEFORGE -> repository.fetchModDetail(modId)
                        ContentSource.MODRINTH -> repository.fetchModrinthDetail(modStringId)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FLAME_LAUNCHER", "상세 정보 로드 실패: ${e.message}")
                } finally {
                    _isLoading.value = false
                }
            }
        } else {
            _isLoading.value = false
        }
    }

    fun onImageClick(index: Int) { _fullscreenIndex.value = index }
    fun dismissFullscreen() { _fullscreenIndex.value = null }
    fun dismissInstallDialog() { _showInstallTargetDialog.value = false }
    fun dismissWorldDialog() { _showWorldDialog.value = false }
    fun dismissVersionDialog() { _showVersionDialog.value = false }
    fun dismissMrVersionDialog() { _showMrVersionDialog.value = false }

    fun onLaunchRequest() {
        viewModelScope.launch { _resultEvents.send(DetailResult.Launch(modId, modKey)) }
    }

    /** "설치" 버튼 — 콘텐츠 종류/소스에 따라 버전 선택 다이얼로그를 띄우거나 바로 설치를 확정한다. */
    fun handleInstallRequest() {
        // Modrinth 모드팩: "어떤 버전을 받을지" 사용자가 고르게 한다.
        if (modSource == ContentSource.MODRINTH && contentType == ContentType.MODPACK) {
            _mrVersionLoading.value = true
            _mrVersionList.value = emptyList()
            _showMrVersionDialog.value = true
            viewModelScope.launch(Dispatchers.IO) {
                val rank = mapOf("release" to 0, "beta" to 1, "alpha" to 2)
                val versions = ModrinthAPI().getVersions(modStringId)
                    .filter { v -> v.files.any { it.primary } || v.files.isNotEmpty() }
                    .sortedWith(
                        compareBy<ModrinthVersion> { rank[it.versionType] ?: 3 }
                            .thenByDescending { it.datePublished ?: "" }
                    )
                _mrVersionList.value = versions
                _mrVersionLoading.value = false
            }
            return
        }

        // 모드팩: "어떤 모드팩 버전(파일)을 받을지" 사용자가 고르게 한다.
        if (contentType == ContentType.MODPACK) {
            _versionLoading.value = true
            _versionFiles.value = emptyList()
            _showVersionDialog.value = true
            viewModelScope.launch(Dispatchers.IO) {
                val files = repository.fetchFilesForDetect(modId)
                    .sortedWith(compareByDescending<CurseForgeFile> { it.fileDate ?: "" }
                        .thenByDescending { it.id })
                _versionFiles.value = files
                _versionLoading.value = false
            }
            return
        }

        if (!contentType.needsTargetInstance) {
            viewModelScope.launch { _resultEvents.send(DetailResult.Install(modId = modId, modKey = modKey)) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (modSource == ContentSource.MODRINTH) {
                val versions = ModrinthAPI().getVersions(modStringId)
                val loaderSet = mutableSetOf<ModLoader>()
                val combos = linkedSetOf<Pair<String, ModLoader?>>()
                val mcVersionSet = mutableSetOf<String>()
                versions.forEach { v ->
                    val vLoaders = v.loaders.mapNotNull { l ->
                        when (l.lowercase()) {
                            "fabric"   -> ModLoader.FABRIC
                            "forge"    -> ModLoader.FORGE
                            "neoforge" -> ModLoader.NEOFORGE
                            else       -> null
                        }
                    }.distinct()
                    loaderSet.addAll(vLoaders)
                    val mcs = v.gameVersions.filter {
                        repository.MC_VERSION_REGEX.matches(it.trim()) &&
                            MinecraftSupport.isSupported(it.trim())
                    }
                    mcVersionSet.addAll(mcs)
                    when {
                        vLoaders.isNotEmpty() -> mcs.forEach { mc -> vLoaders.forEach { l -> combos += mc to l } }
                        !contentType.requiresModLoader -> mcs.forEach { mc -> combos += mc to null }
                    }
                }
                val includeVanilla = !contentType.requiresModLoader && loaderSet.isEmpty()
                val supportedMc = if (contentType == ContentType.DATAPACK) mcVersionSet else emptySet()
                val mcVersionFilter = if (contentType == ContentType.MOD || contentType == ContentType.DATAPACK)
                    mcVersionSet else emptySet()

                _supportedLoaders.value = loaderSet
                _supportedMcVersions.value = supportedMc
                _supportedCombos.value = combos
                    .filter { MinecraftSupport.isSupported(it.first) }
                    .map { (v, l) -> VersionLoaderCombo(v, l, repository.mcVersionSortKey(v)) }
                    .sortedByDescending { it.sortKey }
                _loaderInstances.value = repository.scanInstances(includeVanilla, loaderSet, mcVersionFilter)
                _showInstallTargetDialog.value = true
                return@launch
            }

            val files = repository.fetchFilesForDetect(modId)
            val supported = repository.extractSupportedLoaders(files)
            _supportedLoaders.value = supported

            val includeVanilla = !contentType.requiresModLoader && supported.isEmpty()
            val supportedMc = if (contentType == ContentType.DATAPACK)
                repository.extractSupportedMcVersions(files)
                    .filter { MinecraftSupport.isSupported(it) }
                    .toSet()
            else emptySet()
            _supportedMcVersions.value = supportedMc

            val mcVersionFilter = if (contentType == ContentType.MOD || contentType == ContentType.DATAPACK)
                repository.extractSupportedMcVersions(files)
                    .filter { MinecraftSupport.isSupported(it) }
                    .toSet()
            else emptySet()

            _supportedCombos.value = repository.extractVersionLoaderCombos(files, includeVanilla)
                .filter { MinecraftSupport.isSupported(it.mcVersion) }
            _loaderInstances.value = repository.scanInstances(includeVanilla, supported, mcVersionFilter)
            _showInstallTargetDialog.value = true
        }
    }

    private val _toastEvents = Channel<String>(Channel.BUFFERED)
    val toastEvents = _toastEvents.receiveAsFlow()

    fun onUseExisting(instance: InstanceSummary) {
        if (contentType == ContentType.DATAPACK) {
            val worlds = repository.listWorldsForInstance(instance)
            if (worlds.isEmpty()) {
                viewModelScope.launch { _toastEvents.send("이 인스턴스에 월드가 없습니다. 먼저 게임에서 월드를 만들어 주세요.") }
            } else {
                pendingDatapackInstance = instance
                _worldCandidates.value = worlds
                _showInstallTargetDialog.value = false
                _showWorldDialog.value = true
            }
        } else {
            _showInstallTargetDialog.value = false
            viewModelScope.launch {
                _resultEvents.send(DetailResult.Install(modId = modId, modKey = modKey, targetInstanceId = instance.id))
            }
        }
    }

    fun onCreateNew(version: String, loader: ModLoader?) {
        _showInstallTargetDialog.value = false
        viewModelScope.launch {
            _resultEvents.send(
                DetailResult.Install(
                    modId = modId, modKey = modKey,
                    targetVersion = version, targetLoader = loader,
                    targetNewToken = kr.co.donghyun.flamelauncher.data.instance.InstanceManager.newInstanceToken(),
                )
            )
        }
    }

    fun onWorldSelected(world: String) {
        _showWorldDialog.value = false
        val instance = pendingDatapackInstance ?: return
        viewModelScope.launch {
            _resultEvents.send(
                DetailResult.Install(
                    modId = modId, modKey = modKey,
                    targetInstanceId = instance.id, targetWorld = world,
                )
            )
        }
    }

    fun onVersionFileSelected(file: CurseForgeFile) {
        _showVersionDialog.value = false
        viewModelScope.launch {
            _resultEvents.send(DetailResult.Install(modId = modId, modKey = modKey, targetFileId = file.id))
        }
    }

    fun onMrVersionSelected(version: ModrinthVersion) {
        _showMrVersionDialog.value = false
        viewModelScope.launch {
            _resultEvents.send(DetailResult.Install(modId = modId, modKey = modKey, targetMrVersionId = version.id))
        }
    }
}
