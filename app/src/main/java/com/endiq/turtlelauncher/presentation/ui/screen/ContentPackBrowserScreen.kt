package com.endiq.turtlelauncher.presentation.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.data.mods.ContentItem
import com.endiq.turtlelauncher.data.mods.ContentSource
import com.endiq.turtlelauncher.data.mojang.DownloadProgress
import com.endiq.turtlelauncher.presentation.ui.theme.*
import com.endiq.turtlelauncher.presentation.util.window.isTablet
import com.endiq.turtlelauncher.presentation.util.window.isCompact

/**
 * CurseForge classId 기반 컨텐츠 분류.
 * - MODPACK: 4471
 * - MOD: 6
 * - TEXTURE_PACK(Resource Pack): 12
 * - SHADER_PACK: 6552
 */
enum class ContentType(val classId: Int, val label: String) {
    MODPACK(4471, "🗂️ 모드팩"),
    MOD(6, "📂 모드"),
    DATAPACK(6945, "📦 데이터팩"),
    TEXTURE_PACK(12, "🎨 텍스처팩"),
    SHADER_PACK(6552, "📋 쉐이더팩"),
    WORLD(17, "🗺️ 월드");

    val needsWorldSelection: Boolean get() = this == DATAPACK

    /** Modrinth project_type facet 값 (mod/modpack/resourcepack/shader/datapack). */
    val modrinthType: String
        get() = when (this) {
            MODPACK -> "modpack"
            MOD -> "mod"
            DATAPACK -> "datapack"
            TEXTURE_PACK -> "resourcepack"
            SHADER_PACK -> "shader"
            WORLD -> "modpack"   // Modrinth 엔 'world' 타입이 없음 — 검색은 modpack 으로 대체
        }

    /** 설치 시 사용자가 타겟 인스턴스를 골라야 하는 타입. 모드팩만 자체 인스턴스를 만듦. */
    val needsTargetInstance: Boolean
        get() = this != MODPACK

    /** 새 인스턴스를 만들 때 Fabric/Forge 같은 모드 로더가 반드시 필요한지. */
    val requiresModLoader: Boolean
        get() = this == MOD

    /** 하위호환 — 기존 코드에서 호출하던 이름 유지 */
    @Deprecated("requiresModLoader 사용", ReplaceWith("requiresModLoader"))
    val requiresLoader: Boolean get() = requiresModLoader
}

@Composable
fun ContentPackBrowserScreen(
    onBack : () -> Unit,
    contentPacks: List<ContentItem>,
    progress: DownloadProgress,
    isLoading: Boolean,
    isInstalling: Boolean,
    installingModId: String?,
    statusMessage: String,
    selectedSource: ContentSource,
    selectedContentType: ContentType,
    installedIds: Set<String>,
    onSearch: (query: String, type: ContentType) -> Unit,
    onSourceFilter: (ContentSource) -> Unit,
    onContentTypeFilter: (ContentType) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    onInstall: (ContentItem) -> Unit,
    onLaunch: (ContentItem) -> Unit,
    searchError: String? = null,
    selectedMcVersion: String = "",
    availableMcVersions: List<String> = emptyList(),
    onMcVersionFilter: (String) -> Unit = {},
    selectedLoaderFilter: String = "",
    onLoaderFilter: (String) -> Unit = {},
) {
    val tablet = isTablet()
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            if (last >= total - 3 && total > 0 && hasMore && !isLoading) onLoadMore()
        }
    }

    var showCautionDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BgSurface).systemBarsPadding()) {
        Column(modifier = Modifier.border(1.dp, BgBorder, RoundedCornerShape(0.dp)).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface)
                    .padding(horizontal = if (tablet) 16.dp else 10.dp, vertical = if (tablet) 10.dp else 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "컨텐츠 검색",
                    color = TextMain,
                    fontSize = if (tablet) 18.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearch(it, selectedContentType)
                },
                textStyle = TextStyle(color = TextMain, fontSize = if (tablet) 13.sp else 11.sp),
                cursorBrush = SolidColor(Turtle),
                modifier = Modifier
                    .fillMaxWidth(1f)
                    .background(BgDark, RoundedCornerShape(20.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .padding(vertical = if (tablet) 12.dp else 8.dp, horizontal = if (tablet) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 컨텐츠 타입 필터 칩 (Modpack / Mod / TexturePack / ShaderPack)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(ContentType.entries) { type ->
                    val isSelected = selectedContentType == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Turtle else BgDark)
                            .border(1.dp, if (isSelected) Turtle else BgBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                onContentTypeFilter(type)
                                onSearch(searchQuery, type)
                            }
                            .padding(horizontal = if (tablet) 14.dp else 12.dp, vertical = if (tablet) 7.dp else 5.dp)
                    ) {
                        Text(
                            text = type.label,
                            color = if (isSelected) Color.White else TextSub,
                            fontSize = if (tablet) 12.sp else 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 소스(CurseForge/Modrinth) + MC버전 + 모드로더를 한 줄에 다 넣고
            //   전체를 가로 스크롤시킨다(구분선 "|" 으로 그룹을 나눔).
            //   ⚠️ LazyRow 를 이 안에 중첩시키면 폭 계산이 꼬이거나 스크롤 제스처가
            //   서로 충돌하는 문제가 있어서, 바깥 Row 하나에 horizontalScroll 을 걸고
            //   안쪽은 전부 일반 아이템으로 나열한다(다른 화면에서 이미 검증된 패턴).
            val filterRowScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(filterRowScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 소스 선택(CurseForge / Modrinth)
                ContentSource.entries.forEach { source ->
                    val isSelected = selectedSource == source
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Turtle else BgDark)
                            .border(1.dp, if (isSelected) Turtle else BgBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                if (!isSelected) {
                                    onSourceFilter(source)
                                    onSearch(searchQuery, selectedContentType)
                                }
                            }
                            .padding(horizontal = if (tablet) 12.dp else 10.dp, vertical = if (tablet) 8.dp else 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Image(
                                painter = if(source.prefix == "cf") painterResource(R.drawable.img_curseforge_icon)
                                else painterResource(R.drawable.img_modrith_icon),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(if (isSelected) Color.White else TextSub),
                                modifier = Modifier.size(if (tablet) 16.dp else 12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = source.label,
                                color = if (isSelected) Color.White else TextSub,
                                fontSize = if (tablet) 13.sp else 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // 구분선 — 소스 선택과 버전 필터 그룹을 나눔.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(BgBorder)
                )

                // MC 버전 드롭다운
                var versionMenuOpen by remember { mutableStateOf(false) }
                Box {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selectedMcVersion.isNotBlank()) Turtle else BgDark)
                            .border(1.dp, if (selectedMcVersion.isNotBlank()) Turtle else BgBorder, RoundedCornerShape(16.dp))
                            .clickable { versionMenuOpen = true }
                            .padding(horizontal = if (tablet) 12.dp else 10.dp, vertical = if (tablet) 8.dp else 6.dp)
                    ) {
                        Text(
                            text = if (selectedMcVersion.isBlank()) "🧊 전체 버전" else "🧊 $selectedMcVersion",
                            color = if (selectedMcVersion.isNotBlank()) Color.White else TextSub,
                            fontSize = if (tablet) 13.sp else 11.sp,
                            fontWeight = if (selectedMcVersion.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = versionMenuOpen,
                        onDismissRequest = { versionMenuOpen = false },
                        modifier = Modifier.heightIn(max = 320.dp).background(BgSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("전체 버전", color = TextMain) },
                            onClick = { versionMenuOpen = false; onMcVersionFilter("") }
                        )
                        availableMcVersions.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v, color = TextMain) },
                                onClick = { versionMenuOpen = false; onMcVersionFilter(v) }
                            )
                        }
                    }
                }

                // 모드로더 필터 칩 — 같은 줄, 같은 가로 스크롤 안에 이어서 나열.
                //   모드/모드팩일 때만 의미 있음(리소스팩/쉐이더/월드엔 로더가 없음).
                if (selectedContentType == ContentType.MOD || selectedContentType == ContentType.MODPACK) {
                    // 구분선 — 버전 칩과 로더 칩 그룹을 시각적으로 나눔.
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(BgBorder)
                    )
                    listOf("" to "🧰 전체", "fabric" to "Fabric", "forge" to "Forge",
                        "neoforge" to "NeoForge", "quilt" to "Quilt").forEach { (value, label) ->
                        val isSelected = selectedLoaderFilter == value
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Turtle else BgDark)
                                .border(1.dp, if (isSelected) Turtle else BgBorder, RoundedCornerShape(16.dp))
                                .clickable { onLoaderFilter(value) }
                                .padding(horizontal = if (tablet) 12.dp else 10.dp, vertical = if (tablet) 8.dp else 6.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TextSub,
                                fontSize = if (tablet) 12.sp else 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 다운로드 진행은 화면 하단 배너가 아니라 모달 팝업으로 표시한다.
        // (아래 LazyVerticalGrid 뒤, Column 밖에서 띄움)

        // 메인 리스트 레이아웃 Grid 처리 (태블릿은 2열, 폰은 1열 구성 대응)
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (tablet) 2 else 1),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (tablet) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(contentPacks, key = { it.trackKey }) { mod ->
                ContentPackItem(
                    mod = mod,
                    isInstalling = isInstalling && installingModId == mod.trackKey,
                    isInstalled = installedIds.contains(mod.trackKey),
                    onInstall = { onInstall(mod) },
                    onLaunch = { onLaunch(mod) },
                    onDetail = { onInstall(mod) },
                    tablet = tablet
                )
            }

            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Turtle)
                    }
                }
            } else if (contentPacks.isEmpty()) {
                // 검색이 끝났는데 결과가 0개 — 진짜 검색결과가 없는 건지, 네트워크/파싱 오류로
                // 조용히 실패한 건지 사용자가 구분할 수 있게 표시한다(예전엔 그냥 빈 화면이었음).
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (searchError != null) {
                            Text("⚠️ 불러오기 실패", color = Color(0xFFFF6B6B), fontSize = if (tablet) 15.sp else 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(searchError, color = TextSub, fontSize = if (tablet) 12.sp else 10.sp, textAlign = TextAlign.Center)
                        } else {
                            Text("검색 결과가 없어요", color = TextSub, fontSize = if (tablet) 14.sp else 12.sp)
                        }
                    }
                }
            }
        }

        // ── 다운로드 진행 팝업 (모달) ──
        // 설치/다운로드 중에는 무엇을, 어디까지, 얼마나 빠르게 받는지 한 곳에 모아 보여준다.
        // 진행 중에는 사용자가 실수로 닫지 못하게 onDismiss 를 무시한다(자동으로만 사라짐).
        if (isInstalling) {
            DownloadProgressDialog(
                statusMessage = statusMessage,
                progress = progress,
                tablet = tablet,
            )
        }

        if (showCautionDialog) {
            AlertDialog(
                onDismissRequest = { showCautionDialog = false },
                title = { Text("⚠️주의: 모드팩은 제대로 호환되지 않을 수 있습니다.", color = TextPrimary) },
                text = {
                    Text(
                        """
                            모드팩은 기존 Forge/Fabric/NeoForge에 맞게 호환되도록 설계되었습니다.
                            모드팩이 런처에서는 제대로 동작하지 않을 수 있으며, 일부 모드가 호환되지 않을 수 있습니다.
                            크래시 원인을 공유하거나, 오류 원인이 되는 모드들에 대해서 모드를 키거나 끄도록 유도하는 기능을 제공하고 있으나,
                            개발자는 이러한 호환 문제에 대해 Issue를 제공받지 않습니다. 
                            
                            따라서 유저가 활성화된 커뮤니티에서 해결 방안을 논의하는 것을 추천드립니다. 
                        """.trimIndent(),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showCautionDialog = false
                    }) { Text("이해했습니다.", color = Color(0xFFFF6B6B)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCautionDialog = false }) {
                        Text("취소", color = TextSecondary)
                    }
                },
                containerColor = BgSurface,
            )
        }
    }
}

@Composable
fun ContentPackItem(
    mod: ContentItem,
    isInstalling: Boolean,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onDetail: () -> Unit,
    tablet: Boolean
) {
    val context = LocalContext.current
    val compact = isCompact()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tablet) 84.dp else if (compact) 60.dp else 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, if (isInstalled) Turtle else BgBorder, RoundedCornerShape(10.dp))
            // 설치(다운로드) 중인 카드는 탭으로 상세 진입을 막는다.
            .clickable(enabled = !isInstalling) { onDetail() }
            .padding(if (tablet) 12.dp else if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
    ) {
        AsyncImage(
            model = mod.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(if (tablet) 60.dp else if (compact) 40.dp else 48.dp)
                .clip(RoundedCornerShape(8.dp))
                // 설치 중이면 콘텐츠를 흐리게 하여 비활성 상태임을 표시
                .alpha(if (isInstalling) 0.4f else 1f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (isInstalling) 0.4f else 1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = mod.name,
                color = TextMain,
                fontSize = if (tablet) 14.sp else if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mod.summary,
                color = TextSub,
                fontSize = if (tablet) 11.sp else if (compact) 8.sp else 9.sp,
                minLines = if (compact) 1 else 2,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 설치(다운로드) 중인 카드에만 진행 표시
        if (isInstalling) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
            ) {
                CircularProgressIndicator(
                    color = Turtle,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(if (tablet) 22.dp else if (compact) 14.dp else 18.dp)
                )
                if (!compact) {
                    Text(
                        text = context.getString(R.string.installing_label),
                        color = Turtle,
                        fontSize = if (tablet) 11.sp else 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
/**
 * 다운로드 진행 모달 팝업.
 *
 * 표시 정보:
 *  - 단계 메시지(statusMessage) — 예: "OptiFine 다운로드 중...", "Forge 0.x 설치 중..."
 *  - 무엇을 받는지(progress.fileName) — Activity 가 "<파일명> · 12.3 MB / 45.0 MB · 8.4 MB/s"
 *    형태로 합성해 넘긴다. 여기서 ' · ' 로 쪼개 파일명과 상세(크기/속도)를 두 줄로 나눠 보여준다.
 *  - 진행률 막대 + 퍼센트
 *
 * 진행 중에는 사용자가 닫을 수 없다(onDismissRequest 무시) — 설치가 끝나면 호출부가 자동으로 내린다.
 */
@Composable
private fun DownloadProgressDialog(
    statusMessage: String,
    progress: DownloadProgress,
    tablet: Boolean,
) {
    val context = LocalContext.current
    // "<파일명> · 12.3 MB / 45.0 MB · 8.4 MB/s" → 파일명 / 상세 분리
    val rawLabel = progress.fileName
    val firstSep = rawLabel.indexOf(" · ")
    val fileName = if (firstSep >= 0) rawLabel.substring(0, firstSep) else rawLabel
    val detail = if (firstSep >= 0) rawLabel.substring(firstSep + 3) else ""

    AlertDialog(
        onDismissRequest = { /* 진행 중에는 닫지 못함 */ },
        containerColor = BgSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(
                    color = Turtle,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp),
                )
                Text(context.getString(R.string.downloading_label), color = TextMain, fontSize = if (tablet) 16.sp else 14.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                // 단계 메시지
                if (statusMessage.isNotBlank()) {
                    Text(statusMessage, color = TextSub, fontSize = if (tablet) 13.sp else 11.sp)
                }

                // 무엇을 받는지(파일명)
                if (fileName.isNotBlank()) {
                    Text(
                        fileName,
                        color = TextMain,
                        fontSize = if (tablet) 13.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 크기 / 속도 상세
                if (detail.isNotBlank()) {
                    Text(detail, color = Turtle, fontSize = if (tablet) 12.sp else 10.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(2.dp))

                // 진행률 막대 + 퍼센트
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = Turtle,
                    trackColor = BgBorder,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (progress.total > 0) "${progress.current} / ${progress.total}" else "",
                        color = TextSub,
                        fontSize = if (tablet) 11.sp else 9.sp,
                    )
                    Text(
                        "${progress.percent}%",
                        color = Turtle,
                        fontSize = if (tablet) 12.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {},
    )
}
