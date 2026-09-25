import kr.co.donghyun.turtlelauncher.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kr.co.donghyun.turtlelauncher.data.setting.Setting
import kr.co.donghyun.turtlelauncher.data.setting.SettingManager
import kr.co.donghyun.turtlelauncher.presentation.ContentDetail
import kr.co.donghyun.turtlelauncher.presentation.ui.components.HtmlWebView
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextPrimary
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSecondary
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.turtlelauncher.presentation.util.window.isTablet
import kr.co.donghyun.turtlelauncher.presentation.util.window.isCompact

/**
 * 컨텐츠 상세 화면.
 * - 상단: 뒤로가기 + 타이틀
 * - 헤더: 로고, 이름, 다운로드 수, 요약
 * - 스크린샷 캐러셀 (탭하면 onImageClick으로 인덱스 전달 → Activity에서 풀스크린 뷰어 띄움)
 * - 설명 (HTML 파싱된 평문)
 * - 하단 고정 액션: 설치/열기
 *
 * default package에 두는 이유는 기존 코드(`import ModPackDetailScreen`)가
 * default package import를 가정하고 있었기 때문에 그대로 호환되도록 맞춤.
 */
@Composable
fun ContentPackDetailScreen(
    modId: Int,
    modName: String,
    modSummary: String,
    modLogo: String?,
    modDownloads: Long,
    detail: ContentDetail?,
    isLoading: Boolean,
    isInstalled: Boolean,
    contentType: ContentType,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onImageClick: (Int) -> Unit
) {
    val tablet = isTablet()
    val compact = isCompact()

    val context = LocalContext.current
    var showCautionDialog by remember { mutableStateOf(false) }
    var doNotShowAgain by remember { mutableStateOf(false) }

    val setting = SettingManager.load(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        // 상단 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .padding(horizontal = if (tablet) 16.dp else if (compact) 8.dp else 12.dp, vertical = if (tablet) 12.dp else if (compact) 7.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            TextButton(onClick = onBack) {
                Text(context.getString(R.string.back_button), color = TextSub, fontSize = if (tablet) 14.sp else if (compact) 10.sp else 11.sp)
            }
            Text(
                text = context.getString(R.string.details_label),
                color = TextMain,
                fontSize = if (tablet) 17.sp else if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 본문 스크롤 영역
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 16.dp else if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else if (compact) 8.dp else 12.dp)
        ) {
            // 헤더 카드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 14.dp else if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
            ) {
                AsyncImage(
                    model = modLogo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(if (tablet) 88.dp else if (compact) 52.dp else 72.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = modName,
                        color = TextMain,
                        fontSize = if (tablet) 18.sp else if (compact) 12.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "↓ ${formatDownloads(modDownloads)}",
                        color = Turtle,
                        fontSize = if (tablet) 12.sp else if (compact) 9.sp else 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (modSummary.isNotBlank()) {
                        Text(
                            text = modSummary,
                            color = TextSub,
                            fontSize = if (tablet) 12.sp else if (compact) 9.sp else 10.sp,
                            maxLines = if (compact) 2 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 스크린샷 캐러셀
            val screenshots = detail?.screenshots ?: listOf()
            if (screenshots.isNotEmpty()) {
                Text(
                    context.getString(R.string.screenshots_label),
                    color = TextMain,
                    fontSize = if (tablet) 14.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(screenshots.toList().size) { index ->
                        val shot = screenshots[index]
                        AsyncImage(
                            model = shot.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .height(if (tablet) 140.dp else 110.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                                .clickable { onImageClick(index) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            // 설명
            Text(
                context.getString(R.string.description_label),
                color = TextMain,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                    .padding(if (tablet) 14.dp else 12.dp)
            ) {
                when {
                    isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Turtle,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(context.getString(R.string.loading_ellipsis), color = TextSub, fontSize = if (tablet) 12.sp else 11.sp)
                        }
                    }
                    // rawHtml 이 있으면 이미지·태그까지 포함해 WebView 로 그대로 렌더링.
                    detail?.rawHtml?.isNotBlank() == true -> {
                        HtmlWebView(
                            html = detail.rawHtml,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    detail?.description?.isNotBlank() == true -> {
                        Text(
                            text = detail.description,
                            color = TextMain,
                            fontSize = if (tablet) 12.sp else 11.sp,
                            lineHeight = if (tablet) 18.sp else 16.sp
                        )
                    }
                    else -> {
                        Text(
                            text = context.getString(R.string.no_description),
                            color = TextSub,
                            fontSize = if (tablet) 12.sp else 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (tablet) 80.dp else 70.dp))
        }

        // 하단 액션 영역 (고정)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder)
                .padding(horizontal = if (tablet) 16.dp else 12.dp, vertical = if (tablet) 12.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (isInstalled) onLaunch() else {
                    // 모드팩만 "완전하지 않습니다" 경고를 띄운다.
                    // 데이터팩/리소스팩/셰이더 등은 해당 경고가 부적절하므로 바로 설치 흐름으로.
                    if (contentType == ContentType.MODPACK && !setting.neverShowCautionAgain) {
                        showCautionDialog = true
                    } else {
                        onInstall()
                    }
                }},
                colors = ButtonDefaults.buttonColors(containerColor = Turtle),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(if (tablet) 48.dp else 42.dp)
            ) {
                Text(
                    text = if (isInstalled) context.getString(R.string.open_button) else context.getString(R.string.install_label),
                    color = Color.White,
                    fontSize = if (tablet) 14.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (showCautionDialog && !setting.neverShowCautionAgain) {
            AlertDialog(
                onDismissRequest = { showCautionDialog = false },
                title = { Text(context.getString(R.string.modpack_incomplete_warning), color = TextPrimary) },
                text = {
                    Column {
                        Text(
                            text = """
                        일부 모드팩은 강제로 크래시가 발생하거나, 이에 따라 앱이 종료되는 경험을 겪을 수 있습니다.
                        이는 모드팩들이 JAVA를 기반으로 만들어진 것이 많기 때문에 발생하는 문제로써 해당 문제는 직접적으로 해결할 수 없습니다.
                        크래시에 대한 원인 규명을 제공하고, 일부 모드를 ON/OFF 하는 편의적 기능은 제공하지만, 완전한 해결책이 될 수 없습니다.
                        
                        따라서 유저 간의 커뮤니티를 형성하여 직접 해결하는 것을 추천드리며, 개발자는 모드팩 설치로 인한 직/간접적인 이슈에 대해서 책임지지 않습니다.
                    """.trimIndent(),
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // '다시 보지 않기' 체크박스 영역
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                // 🔥 체크박스의 기본 내부 여백만큼 왼쪽으로 당겨서 본문과 좌측 정렬을 맞춤
                                .offset(x = (-12).dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { doNotShowAgain = !doNotShowAgain }
                                )
                        ) {
                            Checkbox(
                                checked = doNotShowAgain,
                                onCheckedChange = { doNotShowAgain = it }
                            )
                            // Checkbox 자체의 여백이 있으므로 Spacer는 제거하거나 폭을 줄이는 것이 좋습니다.
                            Text(
                                text = context.getString(R.string.dont_show_again),
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showCautionDialog = false
                        onInstall()
                        SettingManager.save(context, setting.copy(neverShowCautionAgain = doNotShowAgain))
                    }) { Text(context.getString(R.string.understood_button), color = TextMain) }
                },
                dismissButton = {
                    TextButton(onClick = { showCautionDialog = false }) {
                        Text(context.getString(R.string.cancel_button), color = TextSecondary)
                    }
                },
                containerColor = BgSurface,
            )
        }
    }
}

/** 1,234 / 12.3K / 1.2M 형태로 표시 */
private fun formatDownloads(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
}