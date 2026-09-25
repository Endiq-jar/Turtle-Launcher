package kr.co.donghyun.flamelauncher.presentation

import androidx.compose.ui.platform.LocalContext
import kr.co.donghyun.flamelauncher.R
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.data.mapper.toData
import kr.co.donghyun.flamelauncher.data.mapper.toDomain
import kr.co.donghyun.flamelauncher.BuildConfig
import kr.co.donghyun.flamelauncher.data.setting.Setting
import kr.co.donghyun.flamelauncher.data.setting.SettingManager
import kr.co.donghyun.flamelauncher.data.update.GithubRelease
import kr.co.donghyun.flamelauncher.data.update.GithubUpdateChecker
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.main.MainViewModel
import kr.co.donghyun.flamelauncher.presentation.ui.components.UpdateAvailableDialog
import kr.co.donghyun.flamelauncher.presentation.ui.motion.TurtleMotion
import kr.co.donghyun.flamelauncher.presentation.ui.screen.MainScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlameLauncherTheme

/**
 * MainActivity — Clean Architecture 마이그레이션 1호 화면.
 * 다운로드/설치/로그인상태/인스턴스목록 등 실질적인 로직은 전부 MainViewModel(→UseCase→
 * Repository→domain) 으로 옮겼고, 여기는 다음만 담당하는 얇은 껍데기다:
 *  - Hilt 로 ViewModel 주입받기
 *  - 다른 Activity 열기/결과 받기(ActivityResultLauncher는 Android API라 ViewModel이 할 수 없음)
 *  - ViewModel의 domain 모델 ↔ MainScreen(기존 UI, data 모델 그대로 사용) 사이 타입 매핑
 *    (MainScreen.kt 자체는 이번 마이그레이션 범위에서 제외 — UI 대량 리스크 회피)
 */
@AndroidEntryPoint
class MainActivity : BaseActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var loginErrorMessage by mutableStateOf<String?>(null)
    private var launcherSetting by mutableStateOf(Setting())

    private val minecraftLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            val error = result.data?.getStringExtra(LoginActivity.RESULT_ERROR)
            if (error != null) loginErrorMessage = error
        } else if (result.resultCode == RESULT_OK) {
            loginErrorMessage = null
            viewModel.refreshLoginState()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreated() {
        launcherSetting = SettingManager.load(this)
        TurtleMotion.configure(launcherSetting.animationsEnabled)
        hideNavigation()
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT))

        // ViewModel이 흘려보내는 "설치/실행 준비 완료" 1회성 이벤트 구독 → MinecraftActivity 실행.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.launchEvents.collect { params ->
                    MinecraftActivity.startForResult(
                        this@MainActivity,
                        minecraftLauncher,
                        versionId = params.versionId,
                        assetIndex = params.assetIndexId,
                        extraJars = params.extraJars,
                        mainClass = params.mainClass,
                        instanceDir = params.instanceDirPath,
                    )
                }
            }
        }

        setContent {
            // Reconfigure on state changes so the accessibility/low-end switch takes effect
            // without recreating the Activity. Existing screen call sites stay source-compatible.
            TurtleMotion.configure(launcherSetting.animationsEnabled)
            FlameLauncherTheme {
                val versions by viewModel.versions.collectAsState()
                val progress by viewModel.progress.collectAsState()
                val selected by viewModel.selectedVersion.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                val instances by viewModel.instances.collectAsState()
                val session by viewModel.session.collectAsState()
                val launchingInstance by viewModel.launchingInstance.collectAsState()
                val installError by viewModel.installError.collectAsState()

                LaunchedEffect(installError) {
                    installError?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        viewModel.clearInstallError()
                    }
                }

                // ── GitHub 릴리스 기반 업데이트 자동 감지 ──
                // 앱 실행 시 1회 최신 릴리스를 확인해, 현재 버전보다 높고 "건너뛰기"하지 않은
                // 버전이면 업데이트 팝업을 띄운다. (실패/네트워크 없음 → 조용히 무시)
                var updateRelease by remember { mutableStateOf<GithubRelease?>(null) }
                LaunchedEffect(Unit) {
                    val skipped = SettingManager.load(this@MainActivity).skippedUpdateVersion
                    val release = GithubUpdateChecker.fetchUpdate(BuildConfig.VERSION_NAME)
                    if (release != null && release.tagName != skipped) {
                        updateRelease = release
                    }
                }

                MainScreen(
                    versions = versions.map { it.toData() },
                    instances = instances.map { it.toData() },
                    progress = progress.toData(),
                    selectedVersion = selected?.toData(),
                    isLoading = isLoading,
                    onVersionSelect = { v -> viewModel.selectVersion(v.toDomain()) },
                    onDownloadAndPlay = { version -> viewModel.downloadVanilla(version.toDomain()) },
                    onLaunchFabric = { v, l -> viewModel.downloadFabric(v.toDomain(), l) },
                    onLaunchForge  = { v, f -> viewModel.downloadForge(v.toDomain(), f, false) },
                    onLaunchInstance = { meta -> viewModel.launchInstance(meta.toDomain()) },
                    onOpenInstanceSettings = { meta ->
                        InstanceSettingsActivity.start(this@MainActivity, meta.id, meta.name)
                    },
                    onOpenContents = { ContentPackBrowserActivity.start(this@MainActivity) },
                    onOpenNetworkSettings = { NetworkSettingsActivity.start(this@MainActivity) },
                    onOpenKeySettings = { KeyboardLayoutEditorActivity.start(this@MainActivity) },
                    onOpenSettings = { SettingsActivity.start(this@MainActivity) },
                    onOpenRendererSettings = { SettingsActivity.start(this@MainActivity) },
                    onOpenAssistant = { AssistantActivity.start(this@MainActivity) },
                    onOpenFiles = { FileManagerActivity.start(this@MainActivity) },
                    backgroundUri = launcherSetting.backgroundUri,
                    uuid = session?.uuid,
                    isLoggedIn = session != null,
                    username = session?.username,
                    onLogin = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) },
                    onLaunchNeoForge = { v, f -> viewModel.downloadForge(v.toDomain(), f, true) },
                    launchingInstance = launchingInstance?.toData(),
                )


                updateRelease?.let { release ->
                    UpdateAvailableDialog(
                        release = release,
                        onDownload = { updateRelease = null },
                        onLater = { updateRelease = null },
                        onSkip = {
                            val cur = SettingManager.load(this@MainActivity)
                            SettingManager.save(
                                this@MainActivity,
                                cur.copy(skippedUpdateVersion = release.tagName),
                            )
                            updateRelease = null
                        },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.clearLaunchingPopup()
    }

    override fun onResume() {
        super.onResume()
        launcherSetting = SettingManager.load(this)
        TurtleMotion.configure(launcherSetting.animationsEnabled)
        viewModel.refreshLoginState()
        viewModel.refreshInstances()
    }
}

