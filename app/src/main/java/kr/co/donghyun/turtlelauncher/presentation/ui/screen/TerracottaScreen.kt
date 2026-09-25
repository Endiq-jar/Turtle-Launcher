/*
 * Minimal Terracotta LAN-over-internet UI for TurtleLauncher.
 *
 * This is original UI written for the minimal port; it drives TerracottaController.
 * Backend (Terracotta/EasyTier) is ported from ZalithLauncher2 (GPL-3.0) — see NOTICE.
 *
 * 흐름:
 *  - 대기(Waiting): "룸 만들기"(호스트) / "코드로 참여"(게스트) 선택
 *  - 호스트 진행: HostScanning → HostStarting → HostOK(룸 코드 표시·복사)
 *  - 게스트 진행: GuestConnecting → GuestStarting → GuestOK(접속 완료)
 *  - 예외: Exception(메시지 표시 + 대기 복귀)
 */
package kr.co.donghyun.turtlelauncher.presentation.ui.screen

import kr.co.donghyun.turtlelauncher.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Green
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.donghyun.turtlelauncher.presentation.util.terracota.TerracottaController
import kr.co.donghyun.turtlelauncher.presentation.util.terracota.TerracottaState
import androidx.compose.ui.res.stringResource
import kr.co.donghyun.turtlelauncher.presentation.ui.motion.TurtleMotion
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgItem
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.turtlelauncher.presentation.util.window.isCompact


/**
 * Terracotta 최소 다이얼로그.
 *
 * @param controller 이미 start() 된 컨트롤러
 * @param userName   플레이어 이름(없으면 null → 익명)
 */
@Composable
fun TerracottaDialog(
    controller: TerracottaController,
    userName: String?,
    onClose: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val compact = isCompact()

    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = TurtleMotion.dialogEnter,
            exit = TurtleMotion.dialogExit,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (compact) 0.96f else 0.92f)
                // 세로가 짧은 휴대폰(가로 모드)에서 내용이 많아지면(룸 코드 입력 등)
                // 화면 높이를 넘겨 하단 버튼이 잘리므로 최대 높이를 제한하고 스크롤을 허용한다.
                .heightIn(max = maxDialogHeight)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            // 헤더
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    context.getString(R.string.online_lan_multiplayer),
                    color = TextMain, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            when (val s = state) {
                null, is TerracottaState.Waiting -> WaitingContent(controller, userName, context)
                is TerracottaState.HostScanning,
                is TerracottaState.HostStarting -> ProgressContent(context.getString(R.string.creating_room), context.getString(R.string.preparing_code_for_friend))
                is TerracottaState.HostOK -> HostOkContent(s, controller, context)
                is TerracottaState.GuestConnecting,
                is TerracottaState.GuestStarting -> ProgressContent(context.getString(R.string.connecting_ellipsis), context.getString(R.string.connecting_to_host_world))
                is TerracottaState.GuestOK -> GuestOkContent(controller)
                is TerracottaState.Exception -> ExceptionContent(s, controller)
                else -> ProgressContent(context.getString(R.string.processing_ellipsis), null)
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Turtle),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(context.getString(R.string.close_button), color = Color.White) }

        }
    }
}
}

@Composable
private fun WaitingContent(
    controller: TerracottaController,
    userName: String?,
    context: Context,
) {
    val context = LocalContext.current
    var showJoin by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }
    val compact = isCompact()
    // 상태 폴링(50ms 간격)이 반영되기 전에 연타하면 방/참여 요청이 중복 전송될 수 있어
    // 한 번 누르면 이 화면이 떠 있는 동안은 다시 누르지 못하게 막는다.
    var actionRequested by remember { mutableStateOf(false) }

    Text(
        context.getString(R.string.lan_intro_explainer),
        color = TextSub, fontSize = if (compact) 10.sp else 12.sp
    )

    // 호스트
    ActionButton(
        title = context.getString(R.string.create_room_host),
        subtitle = context.getString(R.string.share_my_lan_world),
        accent = Turtle,
        enabled = !actionRequested,
    ) {
        if (!actionRequested) {
            actionRequested = true
            controller.hostRoom(userName)
        }
    }

    // 게스트
    ActionButton(
        title = context.getString(R.string.join_with_code_guest),
        subtitle = context.getString(R.string.join_with_friends_room_code),
        accent = Green,
        enabled = !actionRequested,
    ) {
        showJoin = !showJoin
    }

    if (showJoin) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; codeError = false },
            label = { Text(context.getString(R.string.room_code_label), color = TextSub) },
            singleLine = true,
            isError = codeError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                unfocusedBorderColor = BgBorder,
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                cursorColor = Green
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (codeError) {
            Text(context.getString(R.string.invalid_room_code), color = Red, fontSize = 11.sp)
        }
        Button(
            enabled = !actionRequested,
            onClick = {
                val trimmed = code.trim()
                if (trimmed.isEmpty() || !controller.isValidRoomCode(trimmed)) {
                    codeError = true
                } else {
                    actionRequested = true
                    val ok = controller.joinRoom(trimmed, userName)
                    if (!ok) {
                        actionRequested = false
                        codeError = true
                        Toast.makeText(context, context.getString(R.string.join_failed_check_code), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(context.getString(R.string.join_button), color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 14.sp)
        }
    }
}

@Composable
private fun HostOkContent(
    state: TerracottaState.HostOK,
    controller: TerracottaController,
    context: Context,
) {
    val context = LocalContext.current
    val code = state.code ?: context.getString(R.string.no_code_placeholder)
    val compact = isCompact()

    Text(context.getString(R.string.room_opened), color = Green, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold)
    Text(context.getString(R.string.send_code_to_friend), color = TextSub, fontSize = if (compact) 10.sp else 12.sp)

    // 코드 박스
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgItem)
            .border(1.dp, Turtle, RoundedCornerShape(10.dp))
            .clickable { copyToClipboard(context, "Terracotta room code", code) }
            .padding(vertical = if (compact) 12.dp else 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            color = TextMain, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    Text(context.getString(R.string.tap_code_to_copy), color = TextSub, fontSize = if (compact) 9.sp else 10.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { copyToClipboard(context, "Terracotta room code", code) },
            colors = ButtonDefaults.buttonColors(containerColor = Turtle),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        ) { Text(context.getString(R.string.copy_code), color = Color.White, fontSize = if (compact) 12.sp else 14.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }

        OutlinedButton(
            onClick = { controller.reset() },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        ) { Text(context.getString(R.string.close_room), color = TextSub, fontSize = if (compact) 12.sp else 14.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

@Composable
private fun GuestOkContent(controller: TerracottaController) {
    val context = LocalContext.current
    val compact = isCompact()
    Text(context.getString(R.string.connected_done), color = Green, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold)
    Text(
        context.getString(R.string.lan_world_shows_in_multiplayer),
        color = TextSub, fontSize = if (compact) 10.sp else 12.sp
    )
    OutlinedButton(
        onClick = { controller.reset() },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Text(context.getString(R.string.disconnect_button), color = TextSub, fontSize = if (compact) 12.sp else 14.sp) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressContent(title: String, subtitle: String?) {
    val compact = isCompact()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        LoadingIndicator(
            modifier = Modifier.size(if (compact) 36.dp else 48.dp),
            color = Turtle
        )
        Column {
            Text(title, color = TextMain, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = TextSub, fontSize = if (compact) 9.sp else 11.sp,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ExceptionContent(
    state: TerracottaState.Exception,
    controller: TerracottaController,
) {
    val context = LocalContext.current
    val compact = isCompact()
    val msg = runCatching { stringResource(state.getEnumType().textRes) }
        .getOrElse { context.getString(R.string.unknown_error) }

    Text(context.getString(R.string.error_occurred), color = Red, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold)
    Text(msg, color = TextSub, fontSize = if (compact) 10.sp else 12.sp)
    Button(
        onClick = { controller.reset() },
        colors = ButtonDefaults.buttonColors(containerColor = Turtle),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Text(context.getString(R.string.retry_button), color = Color.White, fontSize = if (compact) 12.sp else 14.sp) }
}

@Composable
private fun ActionButton(
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val compact = isCompact()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgItem)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = accent, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(subtitle, color = TextSub, fontSize = if (compact) 9.sp else 11.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.copied_done), Toast.LENGTH_SHORT).show()
}