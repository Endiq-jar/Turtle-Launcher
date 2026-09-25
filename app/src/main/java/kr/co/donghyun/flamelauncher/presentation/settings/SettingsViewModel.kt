package kr.co.donghyun.flamelauncher.presentation.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.data.renderer.Renderer
import kr.co.donghyun.flamelauncher.data.renderer.RendererManager
import kr.co.donghyun.flamelauncher.data.setting.Setting
import kr.co.donghyun.flamelauncher.data.setting.SettingManager
import kr.co.donghyun.flamelauncher.data.auth.LocalSkinManager
import kr.co.donghyun.flamelauncher.domain.model.JvmSettings
import kr.co.donghyun.flamelauncher.domain.usecase.GetJvmSettingsUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.ResetJvmSettingsUseCase
import kr.co.donghyun.flamelauncher.domain.usecase.SaveJvmSettingsUseCase
import javax.inject.Inject

/**
 * SettingsScreen(JVM/게임플레이 설정) ViewModel.
 *
 * ⚠️ 렌더러(Renderer enum) 부분은 domain.repository 를 새로 만들지 않고 RendererManager 를
 * 여기서 직접 호출한다 — Renderer 는 아이콘/설명 등 UI 부가 정보가 풍부하게 얽힌 열거형이라
 * 순수 도메인 모델로 복제하는 비용 대비 실익이 낮다고 판단한 의도적 스코프 결정이다.
 * (JVM 힙/GC 등 핵심 설정은 domain.repository.JvmSettingsRepository 로 정식 분리했다.)
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getJvmSettingsUseCase: GetJvmSettingsUseCase,
    private val saveJvmSettingsUseCase: SaveJvmSettingsUseCase,
    private val resetJvmSettingsUseCase: ResetJvmSettingsUseCase,
) : ViewModel() {

    private val _settings = MutableStateFlow(JvmSettings())
    val settings: StateFlow<JvmSettings> = _settings.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _globalRenderer = MutableStateFlow(RendererManager.load(context))
    val globalRenderer: StateFlow<Renderer> = _globalRenderer.asStateFlow()

    private val _launcherSetting = MutableStateFlow(SettingManager.load(context))
    val launcherSetting: StateFlow<Setting> = _launcherSetting.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _settings.value = getJvmSettingsUseCase()
        }
    }

    /** 설정 값을 갱신하고 즉시 저장한다(기존 화면의 "필드 바꾸면 바로 저장" 동작 그대로). */
    fun updateSettings(newSettings: JvmSettings) {
        _settings.value = newSettings
        _saved.value = false
        viewModelScope.launch(Dispatchers.IO) { saveJvmSettingsUseCase(newSettings) }
    }

    fun resetSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            _settings.value = resetJvmSettingsUseCase()
            _saved.value = false
        }
    }

    fun saveExplicitly() {
        viewModelScope.launch(Dispatchers.IO) {
            saveJvmSettingsUseCase(_settings.value)
            _saved.value = true
        }
    }

    fun setGlobalRenderer(renderer: Renderer) {
        _globalRenderer.value = renderer
        viewModelScope.launch(Dispatchers.IO) { RendererManager.save(context, renderer) }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        val updated = _launcherSetting.value.copy(animationsEnabled = enabled)
        _launcherSetting.value = updated
        viewModelScope.launch(Dispatchers.IO) { SettingManager.save(context, updated) }
    }

    fun setBackgroundUri(uri: String?) {
        val updated = _launcherSetting.value.copy(backgroundUri = uri)
        _launcherSetting.value = updated
        viewModelScope.launch(Dispatchers.IO) { SettingManager.save(context, updated) }
    }

    fun importSkin(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!LocalSkinManager.importSkin(context, uri)) return@launch
            val updated = _launcherSetting.value.copy(skinUri = LocalSkinManager.skinFile(context).absolutePath)
            _launcherSetting.value = updated
            SettingManager.save(context, updated)
        }
    }

    fun clearSkin() {
        LocalSkinManager.clear(context)
        val updated = _launcherSetting.value.copy(skinUri = null)
        _launcherSetting.value = updated
        viewModelScope.launch(Dispatchers.IO) { SettingManager.save(context, updated) }
    }
}
