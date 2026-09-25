package kr.co.donghyun.turtlelauncher.presentation.ui.components

import kr.co.donghyun.turtlelauncher.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgItem
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.turtlelauncher.presentation.util.window.isCompact

/**
 * 기기 비호환으로 자동 비활성화된 모드를 알리는 오버레이.
 *
 * 게임의 첫 프레임이 렌더된 뒤(= 게임이 정상 진입한 직후) 한 번 표시된다.
 * 실행 전 [MinecraftActivity.disableUnsupportedMods] 가 데스크탑 전용(arm64 미지원
 * 네이티브 포함) 모드를 `.jar.pingdisabled` 로 비활성화하면서 그 목록을 수집하고,
 * 여기로 전달한다.
 *
 * @param disabled  비활성화된 모드 표시명 목록(파일명 기반). 비어 있으면 호출되지 않는다.
 * @param onClose   닫기(이후 다시 표시되지 않음)
 */
@Composable
fun DisabledModsOverlay(
    disabled: List<DisabledModInfo>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val compact = isCompact()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (compact) 0.94f else 0.86f)
                .fillMaxHeight(0.7f)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                .padding(if (compact) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            // 헤더
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.disabled_mods_label),
                    color = TextMain, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) { Text(context.getString(R.string.confirm_button), color = Turtle, fontSize = if (compact) 11.sp else 13.sp) }
            }

            Text(
                context.getString(R.string.auto_disabled_incompatible_mods, disabled.size) +
                        context.getString(R.string.desktop_only_native_not_android_note) +
                        context.getString(R.string.game_runs_excluding_these_mods),
                color = TextSub, fontSize = if (compact) 10.sp else 12.sp, lineHeight = if (compact) 14.sp else 17.sp
            )

            // 목록(스크롤)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgItem)
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
            ) {
                disabled.forEach { mod ->
                    Column {
                        Text(mod.displayName, color = TextMain, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(mod.reason, color = TextSub, fontSize = if (compact) 9.sp else 11.sp,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }

            Text(
                context.getString(R.string.disabled_mods_not_deleted_note) +
                        context.getString(R.string.revert_via_file_manager_hint),
                color = TextSub, fontSize = if (compact) 9.sp else 10.sp, lineHeight = if (compact) 12.sp else 14.sp
            )
        }
    }
}

/**
 * 비활성화된 모드 한 개의 표시 정보.
 * @param displayName  사용자에게 보여줄 이름(원본 .jar 파일명)
 * @param reason       비활성화 사유(짧은 설명)
 */
data class DisabledModInfo(
    val displayName: String,
    val reason: String,
)