package com.endiq.turtlelauncher.presentation.ui.components

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.endiq.turtlelauncher.data.update.GithubRelease
import com.endiq.turtlelauncher.data.util.markdownToHtml
import com.endiq.turtlelauncher.presentation.ui.theme.BgSurface
import com.endiq.turtlelauncher.presentation.ui.theme.Turtle
import com.endiq.turtlelauncher.presentation.ui.theme.TextMain
import com.endiq.turtlelauncher.presentation.ui.theme.TextSub
import com.endiq.turtlelauncher.presentation.util.window.isCompact
import com.endiq.turtlelauncher.presentation.util.window.isTablet

/**
 * "업데이트가 있습니다" 팝업. GitHub 릴리스 노트를 HTML 로 렌더해 보여준다.
 *
 * 휴대폰(특히 세로가 짧은 화면)을 고려해:
 *  - 릴리스 노트 높이를 고정값이 아니라 화면 높이 비율로 제한(넘치면 팝업 자체가 잘리는 것 방지).
 *  - 폰/태블릿에 맞춰 폰트·여백을 조절.
 *  - "이 버전 건너뛰기"는 하단 버튼 바로 위 체크박스로 두고, 체크된 채 닫으면 그 버전을 건너뛴다.
 *
 * @param onSkip   해당 태그를 저장해 다시 안내하지 않고 닫기(체크박스 체크 상태로 닫을 때)
 * @param onLater  이번만 닫기(체크 안 함)
 * @param onDownload 다운로드 처리 후 닫기(내부에서 URL 열기까지 하고 이 콜백은 닫기만)
 */
@Composable
fun UpdateAvailableDialog(
    release: GithubRelease,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val tablet = isTablet()
    val compact = isCompact()
    val notesHtml = markdownToHtml(release.body)

    // 체크박스 상태 — 체크된 채로 닫거나 다운로드하면 이 버전을 건너뛴다.
    var skipThisVersion by remember { mutableStateOf(false) }

    // 체크 여부에 따라 "닫기"를 건너뛰기 저장(onSkip) 또는 단순 닫기(onLater)로 분기.
    val closeAction: () -> Unit = { if (skipThisVersion) onSkip() else onLater() }

    // 노트 영역 최대 높이 = 화면 높이의 약 45%. 세로가 짧은 폰(가로 모드 등)에서
    // 팝업이 화면을 넘겨 버튼이 잘리지 않도록 화면 기준으로 상한을 잡는다.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val notesMaxHeight = (screenHeightDp * 0.45f).dp

    AlertDialog(
        onDismissRequest = closeAction,
        containerColor = BgSurface,
        // 좁은 폰에서 좌우 여백을 확보하려 기본 폭 제한을 유지(usePlatformDefaultWidth=true).
        properties = DialogProperties(),
        title = {
            Column {
                Text(
                    "🔥 업데이트가 있습니다",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (tablet) 18.sp else if (compact) 14.sp else 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${release.name} (${release.tagName})",
                    color = Turtle,
                    fontSize = if (tablet) 13.sp else if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 릴리스 노트 — 화면 비율로 높이를 제한하고 스크롤. WebView 는 내용만큼 커진다.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = notesMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (notesHtml.isNotBlank()) {
                        HtmlWebView(
                            html = notesHtml,
                            modifier = Modifier.fillMaxWidth(),
                            baseUrl = release.htmlUrl.ifBlank { null },
                        )
                    } else {
                        Text(
                            "새로운 버전이 준비되었습니다.",
                            color = TextSub,
                            fontSize = if (compact) 12.sp else 13.sp,
                        )
                    }
                }

                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

                // 하단 버튼 바로 위 "이 버전 건너뛰기" 체크박스 — 행 전체를 탭해도 토글.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { skipThisVersion = !skipThisVersion },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = skipThisVersion,
                        onCheckedChange = { skipThisVersion = it },
                        modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = Turtle,
                            uncheckedColor = TextSub,
                            checkmarkColor = TextMain,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "이 버전 건너뛰기",
                        color = TextSub,
                        fontSize = if (compact) 12.sp else 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = release.apkUrl ?: release.htmlUrl
                if (url.isNotBlank()) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                // 다운로드로 닫을 때도 체크 상태를 반영(건너뛰기 저장 or 단순 닫기).
                if (skipThisVersion) onSkip() else onDownload()
            }) {
                Text(
                    "다운로드",
                    color = Turtle,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 13.sp else 14.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = closeAction) {
                Text("닫기", color = TextSub, fontSize = if (compact) 13.sp else 14.sp)
            }
        },
    )
}
