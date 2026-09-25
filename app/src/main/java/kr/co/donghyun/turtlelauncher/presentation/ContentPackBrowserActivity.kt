package kr.co.donghyun.turtlelauncher.presentation

import kr.co.donghyun.turtlelauncher.R
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kr.co.donghyun.turtlelauncher.data.mods.ContentItem
import kr.co.donghyun.turtlelauncher.presentation.base.BaseActivity
import kr.co.donghyun.turtlelauncher.presentation.contentbrowser.ContentBrowserViewModel
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.ContentPackBrowserScreen
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TurtleLauncherTheme
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub

/**
 * 컨텐츠(모드팩/모드/텍스처팩/쉐이더팩) 브라우저 액티비티.
 *
 * Clean Architecture 마이그레이션 완료(Phase 4). 검색/필터는 ContentRepository +
 * ContentBrowserViewModel, 설치 로직은 ContentInstallRepositoryImpl 로 전부 이관했다.
 * 이 Activity는 다음만 담당하는 얇은 껍데기다:
 *  - Hilt로 ViewModel 주입
 *  - ContentPackDetailActivity 결과 수신 → ViewModel 설치 메서드 호출
 *  - ViewModel이 흘려보내는 LaunchParams 이벤트 수신 → MinecraftActivity 시작
 *    (ApplicationContext로는 startActivity에 FLAG_ACTIVITY_NEW_TASK 없이 크래시 나서
 *     Activity 컨텍스트가 있는 여기서만 할 수 있음)
 *
 * CurseForge API 게임 ID: 432 (Minecraft)
 */
@AndroidEntryPoint
class ContentPackBrowserActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ContentPackBrowserActivity::class.java))
        }
    }

    private val viewModel: ContentBrowserViewModel by viewModels()

    private var pendingInstallMod: ContentItem? = null  // 결과 처리 시 어떤 항목이었는지 기억

    /** Detail Activity의 결과 수신 */
    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val modKey = data.getStringExtra(ContentPackDetailActivity.EXTRA_MOD_KEY)
        val action = data.getStringExtra("action") ?: return@registerForActivityResult
        val mod = viewModel.contentPacks.value.firstOrNull { it.trackKey == modKey } ?: pendingInstallMod

        when (action) {
            "install" -> {
                if (mod == null) {
                    return@registerForActivityResult
                }

                val targetInstanceId = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_INSTANCE_ID)
                val targetVersion = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_VERSION)
                val targetLoader = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_LOADER)
                    ?.let { runCatching { ModLoader.valueOf(it) }.getOrNull() }
                val targetWorld = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_WORLD)
                val targetFileId = data.getIntExtra(ContentPackDetailActivity.EXTRA_TARGET_FILE_ID, -1)
                    .takeIf { it > 0 }
                val targetMrVersionId = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_MR_VERSION_ID)
                    ?.takeIf { it.isNotBlank() }
                // 새 인스턴스 고유 분리 토큰(UUID8). 있으면 같은 버전·로더라도 별개 인스턴스 생성.
                val targetNewToken = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_NEW_TOKEN)
                    ?.takeIf { it.isNotBlank() }

                val contentType = viewModel.selectedContentType.value
                Log.d("TURTLE_LAUNCHER",
                    "install request: mod=${mod.name} type=$contentType " +
                            "targetInstance=$targetInstanceId targetVersion=$targetVersion " +
                            "targetLoader=$targetLoader targetWorld=$targetWorld " +
                            "targetFileId=$targetFileId targetMrVersionId=$targetMrVersionId " +
                            "targetNewToken=$targetNewToken")

                when {
                    // 기존 인스턴스 선택 (데이터팩이면 targetWorld 도 함께 전달)
                    targetInstanceId != null ->
                        viewModel.installToExistingInstance(mod, targetInstanceId, contentType, targetWorld)

                    // 새 인스턴스 — loader 가 null 이어도 Vanilla 로 진행되어야 함.
                    //   targetNewToken 이 있으면 고유 id 로 분리 생성(같은 버전 중복 허용).
                    targetVersion != null ->
                        viewModel.installToNewInstance(mod, targetVersion, targetLoader, contentType, targetNewToken)

                    // 그 외 (모드팩 등 — 타겟 선택 없이 바로 설치).
                    //   CurseForge 모드팩: 고른 파일(버전) id(Int) 전달.
                    //   Modrinth 모드팩: 고른 버전 id(String) 전달.
                    else ->
                        viewModel.installDirect(mod, contentType, targetFileId, targetMrVersionId)
                }
            }
            "launch" -> {
                if (mod != null) viewModel.launchMod(mod)
            }
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )

        viewModel.refreshInstalledIds()

        // ViewModel이 흘려보내는 "실행 준비 완료" 이벤트 구독 → MinecraftActivity 실행.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.launchEvents.collect { params ->
                    MinecraftActivity.start(
                        this@ContentPackBrowserActivity,
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
            TurtleLauncherTheme {
                val contentPacks by viewModel.contentPacks.collectAsState()
                val progress by viewModel.progress.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                val isInstalling by viewModel.isInstalling.collectAsState()
                val installingModId by viewModel.installingModId.collectAsState()
                val statusMessage by viewModel.installStatusMessage.collectAsState()
                val selectedSource by viewModel.selectedSource.collectAsState()
                val selectedContentType by viewModel.selectedContentType.collectAsState()
                val installedIds by viewModel.installedIds.collectAsState()
                val hasMore by viewModel.hasMore.collectAsState()
                val searchError by viewModel.searchError.collectAsState()
                val selectedMcVersion by viewModel.selectedMcVersion.collectAsState()
                val selectedLoaderFilter by viewModel.selectedLoaderFilter.collectAsState()
                val availableMcVersions by viewModel.availableMcVersions.collectAsState()

                ContentPackBrowserScreen(
                    onBack = { finish() },
                    contentPacks = contentPacks,
                    progress = progress,
                    isLoading = isLoading,
                    isInstalling = isInstalling,
                    installingModId = installingModId,
                    statusMessage = statusMessage,
                    selectedSource = selectedSource,
                    selectedContentType = selectedContentType,
                    installedIds = installedIds,
                    onSearch = { query, type -> viewModel.debouncedSearch(query, type) },
                    onSourceFilter = { viewModel.changeSource(it) },
                    onContentTypeFilter = { viewModel.changeContentType(it) },
                    onLoadMore = { viewModel.loadMore() },
                    hasMore = hasMore,
                    onInstall = { mod -> openDetailForInstall(mod) },
                    onLaunch = { mod -> viewModel.launchMod(mod) },
                    searchError = searchError,
                    selectedMcVersion = selectedMcVersion,
                    availableMcVersions = availableMcVersions,
                    onMcVersionFilter = { viewModel.changeMcVersionFilter(it) },
                    selectedLoaderFilter = selectedLoaderFilter,
                    onLoaderFilter = { viewModel.changeLoaderFilter(it) },
                )

                // 설치 불가 에러 다이얼로그 — 선택한 인스턴스에 맞는 콘텐츠 버전이 없을 때 등
                val errorDialogMessage by viewModel.errorDialogMessage.collectAsState()
                errorDialogMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissErrorDialog() },
                        containerColor = BgSurface,
                        title = { Text(getString(R.string.cannot_install_title), color = TextMain) },
                        text = { Text(msg, color = TextSub) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.dismissErrorDialog() }) {
                                Text(getString(R.string.confirm_button), color = Turtle)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalledIds()
    }

    // ───── 설치 흐름 ─────

    /** 카드의 "설치" 버튼 → Detail Activity 띄우고 거기서 타겟을 받아옴 */
    private fun openDetailForInstall(mod: ContentItem) {
        pendingInstallMod = mod
        val intent = Intent(this, ContentPackDetailActivity::class.java).apply {
            putExtra(ContentPackDetailActivity.EXTRA_MOD_ID, mod.rawId ?: -1)   // CurseForge 숫자 id(있으면)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_KEY, mod.trackKey)     // 소스 무관 키 ("cf:..","mr:..")
            putExtra(ContentPackDetailActivity.EXTRA_SOURCE, mod.source.name)   // CURSEFORGE | MODRINTH
            putExtra(ContentPackDetailActivity.EXTRA_MOD_STRING_ID, mod.id)     // 소스 내 원본 id(문자열)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_NAME, mod.name)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_SUMMARY, mod.summary)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_LOGO, mod.logoUrl)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_DOWNLOADS, mod.downloads)
            putExtra(ContentPackDetailActivity.EXTRA_CONTENT_TYPE, viewModel.selectedContentType.value.name)
        }
        detailLauncher.launch(intent)
    }
}
