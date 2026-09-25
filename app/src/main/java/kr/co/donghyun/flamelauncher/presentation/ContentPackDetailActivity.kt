package kr.co.donghyun.flamelauncher.presentation


import androidx.compose.ui.platform.LocalContext
import kr.co.donghyun.flamelauncher.R
import ContentPackDetailScreen
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.BuildConfig
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.flamelauncher.presentation.ui.theme.*
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet
import kr.co.donghyun.flamelauncher.data.jvm.isLegacyVersion
import kr.co.donghyun.flamelauncher.data.mods.ContentSource
import kr.co.donghyun.flamelauncher.data.mods.CurseForgeFile
import kr.co.donghyun.flamelauncher.data.mods.CurseForgeListResponse
import kr.co.donghyun.flamelauncher.data.mods.ModrinthVersion
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModrinthAPI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class ContentDetail(
    val screenshots: List<ContentScreenshot> = emptyList(),
    val description: String = "",
    val rawHtml: String = ""
)

data class ContentScreenshot(
    val thumbnailUrl: String,  // 작은 썸네일
    val fullUrl: String        // 원본
)

/** 모드 로더 종류 */
enum class ModLoader(val displayName: String, val curseForgeId: Int) {
    FABRIC("Fabric", 4),
    FORGE("Forge", 1),
    NEOFORGE("NeoForge", 6);
}

/**
 * 기존 인스턴스 요약 정보.
 * @param id 인스턴스 ID
 * @param name 표시명
 * @param gameVersion 마인크래프트 버전 (예: "1.20.1")
 * @param loader 설치된 모드 로더 (null이면 바닐라)
 */
data class InstanceSummary(
    val id: String,
    val name: String,
    val gameVersion: String,
    val loader: ModLoader?
)

/**
 * 모드/모드팩이 실제로 제공하는 (MC 버전 × 로더) 조합 한 개.
 * CurseForge files 응답에서 파일별로 뽑아 중복 제거해 만든다.
 * 사용자가 이 조합 하나를 탭하면 버전·로더를 따로 고를 필요 없이 그대로 새 인스턴스가 만들어진다.
 *
 * @param loader null이면 바닐라(로더 무관 — 텍스처/쉐이더/월드 등)
 * @param sortKey 내림차순 정렬용 비교키(major*10000 + minor*100 + patch). 최신 버전이 위로.
 */
data class VersionLoaderCombo(
    val mcVersion: String,
    val loader: ModLoader?,
    val sortKey: Int
) {
    /** 칩/리스트에 표시할 라벨. 예: "1.20.1 · Forge", "1.21.1 · Vanilla" */
    val label: String get() = "$mcVersion · ${loader?.displayName ?: "Vanilla"}"
}

/**
 * MC 버전 → 사람이 알아보기 쉬운 업데이트 이름.
 * 모르는 버전은 null (호출부에서 버전 숫자만 표시).
 */
fun mcVersionNickname(version: String): String? = when (version) {
    "1.21.8", "1.21.7", "1.21.6" -> "Chase the Skies"
    "1.21.5"                     -> "Spring to Life"
    "1.21.4"                     -> "The Garden Awakens"
    "1.21.2", "1.21.3"           -> "Bundles of Bravery"
    "1.21", "1.21.1"             -> "Tricky Trials"
    "1.20.5", "1.20.6"           -> "Armored Paws"
    "1.20.3", "1.20.4"           -> "Decorated Pots"
    "1.20.2"                     -> "Playful Update"
    "1.20", "1.20.1"             -> "Trails & Tales"
    "1.19.4"                     -> "Feature Preview"
    "1.19", "1.19.1", "1.19.2", "1.19.3" -> "The Wild Update"
    "1.18", "1.18.1", "1.18.2"   -> "Caves & Cliffs II"
    "1.17", "1.17.1"             -> "Caves & Cliffs I"
    "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5" -> "Nether Update"
    "1.15", "1.15.1", "1.15.2"   -> "Buzzy Bees"
    "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4" -> "Village & Pillage"
    "1.13", "1.13.1", "1.13.2"   -> "Update Aquatic"
    "1.12", "1.12.1", "1.12.2"   -> "World of Color"
    else -> null
}

/**
 * Clean Architecture 마이그레이션 완료(Phase 4). 상세조회/버전-로더 감지/인스턴스 스캔은
 * ContentDetailRepositoryImpl, 다이얼로그 플로우·상태는 ContentDetailViewModel 로 이관했다.
 * 이 Activity는 UI 렌더링 + setResult()/finish() 호출(Android API라 ViewModel이 못 함)만 담당한다.
 */
@dagger.hilt.android.AndroidEntryPoint
class ContentPackDetailActivity : BaseActivity() {

    private val viewModel: kr.co.donghyun.flamelauncher.presentation.contentdetail.ContentDetailViewModel by viewModels()

    companion object {
        // "1.x" / "1.x.y" 형태만 매칭 (gameVersions 의 로더 태그와 MC 버전 구분)
        private val MC_VERSION_REGEX = Regex("""^\d+\.\d+(\.\d+)?$""")

        const val EXTRA_MOD_ID = "mod_id"
        const val EXTRA_MOD_KEY = "mod_key"            // 소스 무관 추적 키 ("cf:..","mr:..")
        const val EXTRA_SOURCE = "mod_source"          // CURSEFORGE | MODRINTH
        const val EXTRA_MOD_STRING_ID = "mod_string_id" // 소스 내 원본 id(문자열; Modrinth 용)
        const val EXTRA_MOD_NAME = "mod_name"
        const val EXTRA_MOD_SUMMARY = "mod_summary"
        const val EXTRA_MOD_LOGO = "mod_logo"
        const val EXTRA_MOD_DOWNLOADS = "mod_downloads"
        const val EXTRA_CONTENT_TYPE = "content_type"

        // 설치 결과로 전달되는 추가 정보
        const val EXTRA_TARGET_INSTANCE_ID = "target_instance_id"   // 기존 인스턴스 선택 시
        const val EXTRA_TARGET_VERSION = "target_version"           // 새 인스턴스 생성 시
        const val EXTRA_TARGET_LOADER = "target_loader"             // 새 인스턴스 생성 시 (ModLoader.name)
        const val EXTRA_TARGET_NEW_TOKEN = "target_new_token"       // 새 인스턴스 생성 시: 고유 분리 토큰(UUID8). 있으면 기존과 별개 인스턴스로 만듦
        const val EXTRA_TARGET_WORLD = "target_world"               // 데이터팩 설치 대상 월드명
        const val EXTRA_TARGET_FILE_ID = "target_file_id"           // 모드팩(CurseForge): 사용자가 고른 파일(버전) id (Int)
        const val EXTRA_TARGET_MR_VERSION_ID = "target_mr_version_id" // 모드팩(Modrinth): 사용자가 고른 버전 id (String)

        fun start(
            context: Context,
            modId: Int,
            modName: String,
            modSummary: String,
            modLogo: String?,
            modDownloads: Long,
            contentType: ContentType
        ) {
            context.startActivity(
                Intent(context, ContentPackDetailActivity::class.java).apply {
                    putExtra(EXTRA_MOD_ID, modId)
                    putExtra(EXTRA_MOD_NAME, modName)
                    putExtra(EXTRA_MOD_SUMMARY, modSummary)
                    putExtra(EXTRA_MOD_LOGO, modLogo)
                    putExtra(EXTRA_MOD_DOWNLOADS, modDownloads)
                    putExtra(EXTRA_CONTENT_TYPE, contentType.name)
                }
            )
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                scrim = android.graphics.Color.TRANSPARENT
            )
        )
        val modId = intent.getIntExtra(EXTRA_MOD_ID, -1)
        val modKey = intent.getStringExtra(EXTRA_MOD_KEY) ?: ""
        val modSource = runCatching {
            ContentSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: ContentSource.CURSEFORGE.name)
        }.getOrDefault(ContentSource.CURSEFORGE)
        val modStringId = intent.getStringExtra(EXTRA_MOD_STRING_ID) ?: modId.toString()
        val modName = intent.getStringExtra(EXTRA_MOD_NAME) ?: ""
        val modSummary = intent.getStringExtra(EXTRA_MOD_SUMMARY) ?: ""
        val modLogo = intent.getStringExtra(EXTRA_MOD_LOGO)
        val modDownloads = intent.getLongExtra(EXTRA_MOD_DOWNLOADS, 0)
        val contentType = runCatching {
            ContentType.valueOf(intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: ContentType.MODPACK.name)
        }.getOrDefault(ContentType.MODPACK)

        viewModel.initialize(modId, modKey, modSource, modStringId, modName, contentType)

        // ViewModel이 흘려보내는 결과/토스트 이벤트 구독.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.resultEvents.collect { result ->
                        val intent = when (result) {
                            is kr.co.donghyun.flamelauncher.presentation.contentdetail.DetailResult.Install -> Intent()
                                .putExtra(EXTRA_MOD_ID, result.modId)
                                .putExtra(EXTRA_MOD_KEY, result.modKey)
                                .putExtra("action", "install")
                                .apply {
                                    result.targetInstanceId?.let { putExtra(EXTRA_TARGET_INSTANCE_ID, it) }
                                    result.targetVersion?.let { putExtra(EXTRA_TARGET_VERSION, it) }
                                    result.targetLoader?.let { putExtra(EXTRA_TARGET_LOADER, it.name) }
                                    result.targetWorld?.let { putExtra(EXTRA_TARGET_WORLD, it) }
                                    result.targetFileId?.let { putExtra(EXTRA_TARGET_FILE_ID, it) }
                                    result.targetMrVersionId?.let { putExtra(EXTRA_TARGET_MR_VERSION_ID, it) }
                                    result.targetNewToken?.let { putExtra(EXTRA_TARGET_NEW_TOKEN, it) }
                                }
                            is kr.co.donghyun.flamelauncher.presentation.contentdetail.DetailResult.Launch -> Intent()
                                .putExtra(EXTRA_MOD_ID, result.modId)
                                .putExtra(EXTRA_MOD_KEY, result.modKey)
                                .putExtra("action", "launch")
                        }
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                }
                launch {
                    viewModel.toastEvents.collect { message ->
                        Toast.makeText(this@ContentPackDetailActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        setContent {
            TurtleLauncherTheme {
                val detail by viewModel.detail.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                val isInstalled by viewModel.isInstalled.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    ContentPackDetailScreen(
                        modId = modId,
                        modName = modName,
                        modSummary = modSummary,
                        modLogo = modLogo,
                        modDownloads = modDownloads,
                        detail = detail,
                        isLoading = isLoading,
                        isInstalled = isInstalled,
                        contentType = contentType,
                        onBack = { finish() },
                        onInstall = { viewModel.handleInstallRequest() },
                        onLaunch = { viewModel.onLaunchRequest() },
                        onImageClick = { index -> viewModel.onImageClick(index) }
                    )

                    // 전체화면 이미지 뷰어
                    val fullscreenIndex by viewModel.fullscreenIndex.collectAsState()

                    fullscreenIndex?.let { startIndex ->
                        val screenshots = detail?.screenshots ?: emptyList()
                        Dialog(
                            onDismissRequest = { viewModel.dismissFullscreen() },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.97f))
                            ) {
                                val pagerState = rememberPagerState(
                                    initialPage = startIndex,
                                    pageCount = { screenshots.size }
                                )

                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { viewModel.dismissFullscreen() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = screenshots[page].fullUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.FillWidth
                                        )
                                    }
                                }

                                // 페이지 인디케이터
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 32.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    repeat(screenshots.size) { index ->
                                        val isSelected = pagerState.currentPage == index
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 8.dp else 6.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    if (isSelected) Color.White
                                                    else Color.White.copy(alpha = 0.3f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 설치 타겟 선택 다이얼로그
                    val showInstallDialog by viewModel.showInstallTargetDialog.collectAsState()
                    val loaderInstances by viewModel.loaderInstances.collectAsState()
                    val supportedLoaders by viewModel.supportedLoaders.collectAsState()
                    val supportedMcVersions by viewModel.supportedMcVersions.collectAsState()
                    val supportedCombos by viewModel.supportedCombos.collectAsState()

                    if (showInstallDialog) {
                        InstallTargetDialog(
                            contentType = contentType,
                            allowVanilla = !contentType.requiresModLoader && supportedLoaders.isEmpty(),
                            supportedLoaders = supportedLoaders,
                            supportedMcVersions = supportedMcVersions,
                            supportedCombos = supportedCombos,
                            existingInstances = loaderInstances,
                            hideCreateNew = contentType == ContentType.DATAPACK,
                            onDismiss = { viewModel.dismissInstallDialog() },
                            onUseExisting = { instance -> viewModel.onUseExisting(instance) },
                            onCreateNew = { version, loader -> viewModel.onCreateNew(version, loader) }
                        )
                    }

                    // 데이터팩 월드 선택 다이얼로그
                    val showWorldDialog by viewModel.showWorldDialog.collectAsState()
                    val worldCandidates by viewModel.worldCandidates.collectAsState()
                    if (showWorldDialog) {
                        WorldSelectDialog(
                            worlds = worldCandidates,
                            onDismiss = { viewModel.dismissWorldDialog() },
                            onSelect = { world -> viewModel.onWorldSelected(world) }
                        )
                    }

                    // 모드팩 버전(파일) 선택 다이얼로그
                    val showVersionDialog by viewModel.showVersionDialog.collectAsState()
                    val versionFiles by viewModel.versionFiles.collectAsState()
                    val versionLoading by viewModel.versionLoading.collectAsState()
                    if (showVersionDialog) {
                        ModpackVersionDialog(
                            modName = modName,
                            files = versionFiles,
                            isLoading = versionLoading,
                            onDismiss = { viewModel.dismissVersionDialog() },
                            onSelect = { file -> viewModel.onVersionFileSelected(file) }
                        )
                    }

                    // Modrinth 모드팩 버전 선택 다이얼로그
                    val showMrVersionDialog by viewModel.showMrVersionDialog.collectAsState()
                    val mrVersionList by viewModel.mrVersionList.collectAsState()
                    val mrVersionLoading by viewModel.mrVersionLoading.collectAsState()
                    if (showMrVersionDialog) {
                        ModrinthVersionDialog(
                            modName = modName,
                            versions = mrVersionList,
                            isLoading = mrVersionLoading,
                            onDismiss = { viewModel.dismissMrVersionDialog() },
                            onSelect = { version -> viewModel.onMrVersionSelected(version) }
                        )
                    }
                }
            }
        }
    }
}


/**
 * 모드 설치 시 어떤 인스턴스에 설치할지 선택하는 다이얼로그.
 * - existingInstances 비어있으면 신규 생성 섹션만 노출
 * - 비어있지 않으면 두 섹션 모두 노출
 */
@Composable
private fun InstallTargetDialog(
    contentType: ContentType,
    allowVanilla: Boolean,
    supportedLoaders: Set<ModLoader>,           // ★ 추가
    supportedMcVersions: Set<String> = emptySet(),   // 데이터팩 지원 MC 버전(표기용)
    supportedCombos: List<VersionLoaderCombo> = emptyList(),   // 자동 감지된 (버전×로더) 조합
    existingInstances: List<InstanceSummary>,
    onDismiss: () -> Unit,
    onUseExisting: (InstanceSummary) -> Unit,
    onCreateNew: (version: String, loader: ModLoader?) -> Unit,
    hideCreateNew: Boolean = false,   // 데이터팩 등 — 새 인스턴스 생성 섹션 숨김(인스턴스 선택만)
) {
    val context = LocalContext.current
    val tablet = isTablet()

    // 화면 높이의 90% 를 다이얼로그 최대 높이로. 내용이 짧으면 그만큼만 차지(wrap),
    // 길면 이 값에서 멈추고 내부 LazyColumn 이 스크롤 → 하단 버튼은 항상 보임.
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp


    val titleSize       = if (tablet) 18.sp else 14.sp
    val sectionSize     = if (tablet) 15.sp else 12.sp
    val descSize        = if (tablet) 13.sp else 11.sp
    val labelSize       = if (tablet) 13.sp else 11.sp
    val itemTitleSize   = if (tablet) 14.sp else 11.sp
    val itemSubSize     = if (tablet) 12.sp else 9.sp
    val chipSize        = if (tablet) 13.sp else 11.sp
    val buttonSize      = if (tablet) 13.sp else 11.sp
    val pickerLabelSize = if (tablet) 13.sp else 11.sp

    val dialogWidthRatio  = if (tablet) 0.7f else 0.95f
    val outerPad          = if (tablet) 22.dp else 16.dp
    val verticalGap       = if (tablet) 16.dp else 12.dp
    val sectionGap        = if (tablet) 10.dp else 6.dp
    val listItemPadH      = if (tablet) 14.dp else 10.dp
    val listItemPadV      = if (tablet) 12.dp else 9.dp
    val chipPadH          = if (tablet) 12.dp else 9.dp
    val chipPadV          = if (tablet) 7.dp else 5.dp
    val loaderRowPadV     = if (tablet) 12.dp else 8.dp
    val actionButtonH     = if (tablet) 44.dp else 38.dp

    // ── 로더 후보 — supportedLoaders 가 비어있으면 (감지 실패) 폴백으로 전체 ──
    val loaderOptions: List<ModLoader> = when {
        supportedLoaders.isNotEmpty() -> ModLoader.entries.filter { it in supportedLoaders }
        else -> ModLoader.entries.toList()
    }

    val supportedSingle = supportedLoaders.size == 1
    val loaderLocked = supportedSingle    // 로더가 하나뿐이면 선택 불가, 표시만

    // 자동 감지 조합이 있으면 그걸 1순위로 쓴다. (버전/로더 따로 고를 필요 X)
    // 비어있으면 = 감지 실패 → 아래 정적 폴백(수동 버전/로더 선택) 으로 떨어진다.
    val hasCombos = supportedCombos.isNotEmpty()
    var selectedCombo by remember(supportedCombos) {
        mutableStateOf(supportedCombos.firstOrNull())
    }

    // ── 정적 폴백 (조합 감지 실패 시에만 사용) ──
    val fallbackVersions = listOf("26.2", "1.21.8", "1.21.6", "1.21.5", "1.21.4", "1.21.1", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2")
    var selectedVersion by remember { mutableStateOf(fallbackVersions.first()) }
    var selectedLoader by remember {
        mutableStateOf<ModLoader?>(
            when {
                supportedSingle -> supportedLoaders.first()
                allowVanilla    -> null
                else            -> loaderOptions.firstOrNull() ?: ModLoader.FABRIC
            }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(dialogWidthRatio)
                    // 짧은 기기에서도 버튼이 안 잘리도록 화면 90%로 상한만 두고, 내용은 wrap
                    .heightIn(max = maxDialogHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}
                    // 키보드/내비게이션 바가 떠도 내용이 안 가려지게
                    .imePadding()
                    .padding(outerPad),
                verticalArrangement = Arrangement.spacedBy(verticalGap)
            ) {
                // ── 헤더 (고정) ──
                Text(context.getString(R.string.install_target_select_for_type, contentType.label),
                    color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold)

                // ── 스크롤 영역 (헤더와 하단 버튼을 제외한 전부) ──
                //   하나의 LazyColumn 으로 묶어 weight(1f) 를 줘서
                //   내용이 길어도 하단 버튼이 절대 잘리지 않게 한다.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(verticalGap)
                ) {
                    // ── 안내 줄들 ──
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val supportLine = when {
                                supportedLoaders.isEmpty() && contentType.requiresModLoader ->
                                    context.getString(R.string.supported_loader_info_unavailable)
                                supportedLoaders.isEmpty() ->
                                    context.getString(R.string.content_loader_independent)
                                supportedSingle ->
                                    context.getString(R.string.mod_loader_only_note, supportedLoaders.first().displayName)
                                else ->
                                    "지원 로더: ${supportedLoaders.joinToString(", ") { it.displayName }}"
                            }
                            Text(supportLine, color = TextSub, fontSize = descSize)

                            Text(
                                text = when {
                                    hideCreateNew && existingInstances.isEmpty() ->
                                        context.getString(R.string.no_installable_instances)
                                    hideCreateNew ->
                                        context.getString(R.string.select_instance_for_datapack)
                                    existingInstances.isEmpty() ->
                                        context.getString(R.string.no_compatible_instance_will_create)
                                    else -> context.getString(R.string.add_to_existing_or_create_new)
                                },
                                color = TextSub, fontSize = descSize
                            )
                        }
                    }

                    // ── 기존 인스턴스 ──
                    if (existingInstances.isNotEmpty()) {
                        item {
                            Text(context.getString(R.string.existing_instance_compat_only), color = TextMain,
                                fontSize = sectionSize, fontWeight = FontWeight.Bold)
                        }
                        items(existingInstances) { inst ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BgDark)
                                    .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                                    .clickable { onUseExisting(inst) }
                                    .padding(horizontal = listItemPadH, vertical = listItemPadV),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(inst.name, color = TextMain,
                                        fontSize = itemTitleSize, fontWeight = FontWeight.Bold)
                                    Text(
                                        "MC ${inst.gameVersion} · ${inst.loader?.displayName ?: "Vanilla"}",
                                        color = TextSub, fontSize = itemSubSize
                                    )
                                }
                                Text(context.getString(R.string.select_label), color = Flame,
                                    fontSize = labelSize, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // ── 새 인스턴스 만들기 (데이터팩이면 숨김) ──
                    if (!hideCreateNew) {
                        if (existingInstances.isNotEmpty()) {
                            item { HorizontalDivider(color = BgBorder) }
                        }
                        item {
                            Text(context.getString(R.string.create_new_instance), color = TextMain,
                                fontSize = sectionSize, fontWeight = FontWeight.Bold)
                        }

                        if (hasCombos) {
                            // ── 자동 감지된 (버전 × 로더) 조합을 버전별로 그룹지어 리스트로 표시 ──
                            item {
                                Text(context.getString(R.string.compatible_version_loader_auto_detect), color = TextSub, fontSize = pickerLabelSize)
                            }
                            // 버전 단위로 묶기 (combos 는 이미 최신 버전 → 로더 순으로 정렬돼 있음)
                            val grouped: Map<String, List<VersionLoaderCombo>> =
                                supportedCombos.groupBy { it.mcVersion }

                            grouped.forEach { (version, combos) ->
                                // 버전 헤더 (이름 포함)
                                item(key = "ver_$version") {
                                    val nick = mcVersionNickname(version)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Text("MC $version", color = TextMain,
                                            fontSize = itemTitleSize, fontWeight = FontWeight.Bold)
                                        if (nick != null) {
                                            Text("· $nick", color = TextSub, fontSize = itemSubSize,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                // 해당 버전의 로더 행들
                                items(combos, key = { "${it.mcVersion}_${it.loader?.name ?: "vanilla"}" }) { combo ->
                                    val sel = combo == selectedCombo
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Flame.copy(alpha = 0.18f) else BgDark)
                                            .border(1.dp, if (sel) Flame else BgBorder, RoundedCornerShape(8.dp))
                                            .clickable { selectedCombo = combo }
                                            .padding(horizontal = listItemPadH, vertical = listItemPadV),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 선택 표시 라디오 점
                                        Box(
                                            modifier = Modifier
                                                .size(if (tablet) 16.dp else 14.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (sel) Flame else Color.Transparent)
                                                .border(
                                                    1.5.dp,
                                                    if (sel) Flame else TextSub,
                                                    RoundedCornerShape(50)
                                                )
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            combo.loader?.displayName ?: "Vanilla",
                                            color = if (sel) TextMain else TextSub,
                                            fontSize = itemTitleSize,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sel) {
                                            Text(context.getString(R.string.selected_label), color = Flame,
                                                fontSize = labelSize, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            // ── 폴백: 조합 감지 실패 시 수동으로 버전/로더 선택 ──
                            item {
                                Text(context.getString(R.string.version_label), color = TextSub, fontSize = pickerLabelSize)
                            }
                            item {
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                                ) {
                                    items(fallbackVersions) { v ->
                                        val sel = v == selectedVersion
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (sel) Flame else BgDark)
                                                .border(1.dp, if (sel) Flame else BgBorder, RoundedCornerShape(16.dp))
                                                .clickable { selectedVersion = v }
                                                .padding(horizontal = chipPadH, vertical = chipPadV)
                                        ) {
                                            Text(v,
                                                color = if (sel) Color.White else TextSub,
                                                fontSize = chipSize,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }

                            item {
                                Text(
                                    when {
                                        loaderLocked -> context.getString(R.string.loader_for_this_mod_only)
                                        allowVanilla -> context.getString(R.string.type_label2)
                                        else         -> context.getString(R.string.mod_loader_label)
                                    },
                                    color = TextSub, fontSize = pickerLabelSize
                                )
                            }
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                                ) {
                                    // Vanilla 칩 — allowVanilla 일 때만
                                    if (allowVanilla) {
                                        val sel = selectedLoader == null
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) Flame else BgDark)
                                                .border(1.dp, if (sel) Flame else BgBorder, RoundedCornerShape(8.dp))
                                                .clickable { selectedLoader = null }
                                                .padding(vertical = loaderRowPadV),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Vanilla",
                                                color = if (sel) Color.White else TextSub,
                                                fontSize = chipSize,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }

                                    loaderOptions.forEach { loader ->
                                        val sel = loader == selectedLoader
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) Flame else BgDark)
                                                .border(1.dp, if (sel) Flame else BgBorder, RoundedCornerShape(8.dp))
                                                .let {
                                                    if (loaderLocked) it
                                                    else it.clickable { selectedLoader = loader }
                                                }
                                                .padding(vertical = loaderRowPadV),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(loader.displayName,
                                                color = if (sel) Color.White else TextSub,
                                                fontSize = chipSize,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 하단 버튼 (고정) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(actionButtonH),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSub)
                    ) { Text(context.getString(R.string.cancel_button), fontSize = buttonSize) }
                    // 새로 만들고 설치 버튼 — 데이터팩이면 숨김(인스턴스 선택만)
                    if (!hideCreateNew) {
                        // 조합 모드면 selectedCombo, 폴백이면 selectedVersion/selectedLoader 사용
                        val createEnabled = if (hasCombos) selectedCombo != null else true
                        Button(
                            onClick = {
                                if (hasCombos) {
                                    selectedCombo?.let { onCreateNew(it.mcVersion, it.loader) }
                                } else {
                                    onCreateNew(selectedVersion, selectedLoader)
                                }
                            },
                            enabled = createEnabled,
                            modifier = Modifier.weight(1f).height(actionButtonH),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Flame,
                                disabledContainerColor = BgDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(context.getString(R.string.create_new_and_install),
                                color = if (createEnabled) Color.White else TextSub,
                                fontSize = buttonSize, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
/**
 * 데이터팩 설치 시, 인스턴스 선택 후 그 인스턴스의 월드를 고르는 다이얼로그.
 * 월드는 saves/ 아래 level.dat 가 있는 폴더만 후보로 들어온다(호출부에서 listWorldsForInstance 로 필터).
 */
@Composable
private fun WorldSelectDialog(
    worlds: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val tablet = isTablet()

    val titleSize = if (tablet) 18.sp else 14.sp
    val descSize  = if (tablet) 13.sp else 11.sp
    val itemSize  = if (tablet) 14.sp else 12.sp
    val buttonSize = if (tablet) 13.sp else 11.sp
    val itemPadH  = if (tablet) 14.dp else 12.dp
    val itemPadV  = if (tablet) 12.dp else 10.dp

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = {
            Text(context.getString(R.string.select_world_for_datapack), color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    context.getString(R.string.datapack_installs_to_world_note),
                    color = TextSub,
                    fontSize = descSize
                )
                Spacer(Modifier.height(4.dp))
                worlds.forEach { world ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelect(world) }
                            .padding(horizontal = itemPadH, vertical = itemPadV)
                    ) {
                        Text(
                            text = "🗺️ $world",
                            color = TextMain,
                            fontSize = itemSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel_button), color = TextSub, fontSize = buttonSize)
            }
        },
    )
}
/**
 * 모드팩 "버전(파일) 선택" 다이얼로그.
 *
 * InstallTargetDialog 와 동일한 안 잘리는 레이아웃:
 *   - 바깥 Column 은 heightIn(max = 화면 90%) 으로 상한만 두고 내용은 wrap
 *   - 헤더(제목/안내) 고정, 하단 버튼(취소) 고정
 *   - 가운데 파일 리스트만 LazyColumn weight(1f) 로 스크롤 → 항목이 많아도 다이얼로그가 안 잘림
 *
 * 각 파일에 표시하는 정보:
 *   - displayName (모드팩 릴리스 이름)
 *   - releaseType 배지 (Release / Beta / Alpha)
 *   - 타겟 MC 버전(gameVersions 중 "1.x[.y]")
 *   - 모드로더(gameVersions 중 Forge/Fabric/NeoForge/Quilt)
 *   - 업로드 날짜(fileDate, ISO8601 → yyyy-MM-dd)
 */
@Composable
private fun ModpackVersionDialog(
    modName: String,
    files: List<CurseForgeFile>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (CurseForgeFile) -> Unit,
) {
    val context = LocalContext.current
    val tablet = isTablet()
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    val titleSize    = if (tablet) 18.sp else 14.sp
    val descSize     = if (tablet) 13.sp else 11.sp
    val itemTitle    = if (tablet) 14.sp else 12.sp
    val itemSub      = if (tablet) 12.sp else 10.sp
    val chipSize     = if (tablet) 11.sp else 9.sp
    val buttonSize   = if (tablet) 13.sp else 11.sp

    val dialogWidthRatio = if (tablet) 0.7f else 0.95f
    val outerPad   = if (tablet) 22.dp else 16.dp
    val verticalGap = if (tablet) 16.dp else 12.dp
    val itemPadH   = if (tablet) 14.dp else 11.dp
    val itemPadV   = if (tablet) 12.dp else 10.dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(dialogWidthRatio)
                    .heightIn(max = maxDialogHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}
                    .imePadding()
                    .padding(outerPad),
                verticalArrangement = Arrangement.spacedBy(verticalGap)
            ) {
                // ── 헤더 (고정) ──
                Text(
                    context.getString(R.string.select_version_to_install),
                    color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    context.getString(R.string.modpack_version_pick_desc_auto, modName),
                    color = TextSub, fontSize = descSize
                )

                // ── 가운데 리스트 (스크롤; weight 로 남는 공간 전부 차지) ──
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = Flame, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(context.getString(R.string.loading_version_list), color = TextSub, fontSize = descSize)
                            }
                        }
                        files.isEmpty() -> {
                            Text(
                                context.getString(R.string.no_versions_to_show_retry),
                                color = TextSub, fontSize = descSize,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(files) { file ->
                                    val mcVer = file.gameVersions
                                        .firstOrNull { Regex("""^\d+\.\d+(\.\d+)?$""").matches(it.trim()) }
                                    val loader = file.gameVersions.firstOrNull {
                                        it.equals("Forge", true) || it.equals("Fabric", true) ||
                                                it.equals("NeoForge", true) || it.equals("Quilt", true)
                                    }
                                    val date = formatFileDate(file.fileDate)

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(BgItem)
                                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                                            .clickable { onSelect(file) }
                                            .padding(horizontal = itemPadH, vertical = itemPadV),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 1줄: 릴리스 이름 + releaseType 배지
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = file.displayName,
                                                color = TextMain, fontSize = itemTitle, fontWeight = FontWeight.Bold,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            ReleaseTypeBadge(file.releaseType, chipSize)
                                        }
                                        // 2줄: MC 버전 · 로더 · 날짜 칩
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            InfoChip(text = mcVer?.let { "MC $it" } ?: context.getString(R.string.mc_version_unknown), fg = Flame, fontSize = chipSize)
                                            InfoChip(text = loader ?: context.getString(R.string.loader_unknown), fg = TextSub, fontSize = chipSize)
                                            if (date != null) {
                                                Text("· $date", color = TextSub, fontSize = itemSub, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 하단 버튼 (고정) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(context.getString(R.string.cancel_button), color = TextSub, fontSize = buttonSize)
                    }
                }
            }
        }
    }
}

/** releaseType(1/2/3) → 색 배지 */
@Composable
private fun ReleaseTypeBadge(releaseType: Int, fontSize: androidx.compose.ui.unit.TextUnit) {
    val (label, color) = when (releaseType) {
        1 -> "Release" to Color(0xFF4CAF50)
        2 -> "Beta" to Color(0xFFFFB300)
        else -> "Alpha" to Color(0xFFE53935)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

/** 정보 칩 (MC 버전 / 로더) */
@Composable
private fun InfoChip(text: String, fg: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fg.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, color = fg, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Modrinth 모드팩 "버전 선택" 다이얼로그.
 *
 * CurseForge 의 [ModpackVersionDialog] 와 레이아웃은 동일하지만,
 * 모델이 ModrinthVersion 이고 표시 정보가 다르다:
 *   - 표시 이름: 모델 표준 필드(versionNumber/name)가 모델에 없을 수 있어 파일명으로 폴백
 *   - versionType (release/beta/alpha) 배지 — 문자열이라 [releaseTypeToInt] 로 변환해 기존 배지 재사용
 *   - 타겟 MC 버전(gameVersions 중 "1.x[.y]")
 *   - 모드로더(loaders 첫 항목)
 *   - 게시 날짜(datePublished, ISO8601 → yyyy-MM-dd)
 */
@Composable
private fun ModrinthVersionDialog(
    modName: String,
    versions: List<ModrinthVersion>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ModrinthVersion) -> Unit,
) {
    val context = LocalContext.current
    val tablet = isTablet()
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    val titleSize    = if (tablet) 18.sp else 14.sp
    val descSize     = if (tablet) 13.sp else 11.sp
    val itemTitle    = if (tablet) 14.sp else 12.sp
    val itemSub      = if (tablet) 12.sp else 10.sp
    val chipSize     = if (tablet) 11.sp else 9.sp
    val buttonSize   = if (tablet) 13.sp else 11.sp

    val dialogWidthRatio = if (tablet) 0.7f else 0.95f
    val outerPad   = if (tablet) 22.dp else 16.dp
    val verticalGap = if (tablet) 16.dp else 12.dp
    val itemPadH   = if (tablet) 14.dp else 11.dp
    val itemPadV   = if (tablet) 12.dp else 10.dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(dialogWidthRatio)
                    .heightIn(max = maxDialogHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}
                    .imePadding()
                    .padding(outerPad),
                verticalArrangement = Arrangement.spacedBy(verticalGap)
            ) {
                // ── 헤더 (고정) ──
                Text(
                    context.getString(R.string.select_version_to_install),
                    color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    context.getString(R.string.modpack_version_pick_desc_deps, modName),
                    color = TextSub, fontSize = descSize
                )

                // ── 가운데 리스트 (스크롤) ──
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = Flame, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(context.getString(R.string.loading_version_list), color = TextSub, fontSize = descSize)
                            }
                        }
                        versions.isEmpty() -> {
                            Text(
                                context.getString(R.string.no_versions_to_show_retry),
                                color = TextSub, fontSize = descSize,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(versions) { version ->
                                    val mcVer = version.gameVersions
                                        .firstOrNull { Regex("""^\d+\.\d+(\.\d+)?$""").matches(it.trim()) }
                                    val loader = version.loaders.firstOrNull()
                                        ?.replaceFirstChar { it.uppercase() }
                                    val date = formatFileDate(version.datePublished)
                                    // 표시 이름: 모델에 이름 필드가 없을 수 있어 primary 파일명으로 폴백
                                    val displayName = version.files.firstOrNull { it.primary }?.filename
                                        ?: version.files.firstOrNull()?.filename
                                        ?: "버전 ${mcVer ?: ""}".trim()

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(BgItem)
                                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                                            .clickable { onSelect(version) }
                                            .padding(horizontal = itemPadH, vertical = itemPadV),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 1줄: 버전 이름 + versionType 배지
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = displayName,
                                                color = TextMain, fontSize = itemTitle, fontWeight = FontWeight.Bold,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            ReleaseTypeBadge(releaseTypeToInt(version.versionType), chipSize)
                                        }
                                        // 2줄: MC 버전 · 로더 · 날짜
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            InfoChip(text = mcVer?.let { "MC $it" } ?: context.getString(R.string.mc_version_unknown), fg = Flame, fontSize = chipSize)
                                            InfoChip(text = loader ?: context.getString(R.string.loader_unknown), fg = TextSub, fontSize = chipSize)
                                            if (date != null) {
                                                Text("· $date", color = TextSub, fontSize = itemSub, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 하단 버튼 (고정) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(context.getString(R.string.cancel_button), color = TextSub, fontSize = buttonSize)
                    }
                }
            }
        }
    }
}

/** Modrinth versionType("release"/"beta"/"alpha") → ReleaseTypeBadge 가 쓰는 Int(1/2/3) */
private fun releaseTypeToInt(versionType: String?): Int = when (versionType?.lowercase()) {
    "release" -> 1
    "beta"    -> 2
    else      -> 3   // alpha 및 미상
}

/** ISO8601("2024-03-01T12:34:56.789Z") → "2024-03-01". 파싱 실패/없으면 null. */
private fun formatFileDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    // 가장 흔한 형식: 앞 10자(yyyy-MM-dd)만 떼면 충분. 'T' 앞부분 사용.
    val datePart = iso.substringBefore('T').trim()
    return datePart.takeIf { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) }
}
