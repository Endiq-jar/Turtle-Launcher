package kr.co.donghyun.turtlelauncher.presentation.ui.components

import kr.co.donghyun.turtlelauncher.R
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import kotlinx.coroutines.launch
import kr.co.donghyun.turtlelauncher.presentation.MinecraftActivity
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgItem
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Green
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.turtlelauncher.presentation.util.window.isCompact

/**
 * 인게임 설정 메뉴(오른쪽에서 슬라이드되는 패널).
 *
 * GameControllerView 의 ☰ 버튼으로 열린다. 팝업(중앙 Dialog) 대신 화면 전체를
 * 덮는 전면 Dialog 위에서, 패널이 오른쪽에서 슬라이드되어 들어오는 형태.
 * 조건부로(`if (open) InGameMenuOverlay(...)`) 호출돼도 진입 시 visible 을
 * false→true 로 바꿔 슬라이드 인 애니메이션이 항상 보이도록 한다.
 *
 * 세 구역:
 *  1) GUI(화면 버튼) 표시 토글 — controllerVisible 을 제어
 *  2) 핫바 터치 영역 크기 — 핫바를 탭으로 선택하는 영역 스케일(Auto/1~4)
 *  3) 온라인 LAN(Terracotta) — 룸 만들기/참여 다이얼로그 진입
 *
 * 스크림(바깥 어두운 영역) 탭 또는 '닫기' 로 슬라이드 아웃 후 [onClose] 호출.
 *
 * @param activity                현재 게임 Activity (Terracotta 가 VpnService 에 필요)
 * @param userName                플레이어 이름(없으면 null → 익명)
 * @param controllerVisible       현재 화면 버튼 표시 상태(초기값)
 * @param onToggleController      GUI 표시 토글 요청(실제 전환은 GameControllerView 가 수행)
 * @param onClose                 메뉴 닫기
 */
@Composable
fun InGameMenuOverlay(
    activity: Activity,
    userName: String?,
    controllerVisible: Boolean,
    onToggleController: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // 메뉴 안에서 Terracotta 다이얼로그를 띄울지
    var showTerracotta by remember { mutableStateOf(false) }
    // GUI 토글 상태(뷰의 실제 상태를 초기값으로, 전환 시 뷰에 위임)
    var guiOn by remember { mutableStateOf(controllerVisible) }
    // 핫바 터치 영역 스케일(0=자동, 1~4). MinecraftActivity 의 현재값으로 초기화.
    val mc = activity as? MinecraftActivity
    var hotbarScale by remember { mutableStateOf(mc?.hotbarTouchScaleOverride ?: 0) }

    if (showTerracotta) {
        // Terracotta 진입(자체적으로 VPN 동의 + 호스트/게스트 처리)
        TerracottaEntry(
            activity = activity,
            userName = userName,
            onClose = { showTerracotta = false }
        )
        return
    }

    // 패널 가시성. 진입 직후 true 로 전환 → 슬라이드 인.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // 슬라이드 아웃 후 onClose 호출(애니메이션 길이만큼 지연).
    val scope = rememberCoroutineScope()
    fun dismiss() {
        visible = false
        scope.launch {
            kotlinx.coroutines.delay(220)
            onClose()
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ── 스크림(바깥 어두운 영역) — 탭하면 닫힘 ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(220))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { dismiss() }
                )
            }

            // ── 오른쪽 슬라이드 패널 ──
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(tween(220)) { it },   // 오른쪽 밖 → 안
                exit = slideOutHorizontally(tween(220)) { it }     // 안 → 오른쪽 밖
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(if (isCompact()) 0.94f else 0.86f)
                        .background(
                            BgSurface,
                            RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                        )
                        // 패널 내부 탭이 스크림으로 전파돼 닫히는 것 방지
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                        .verticalScroll(rememberScrollState())
                        .padding(if (isCompact()) 12.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact()) 10.dp else 14.dp)
                ) {
                    // 헤더
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            context.getString(R.string.ingame_settings_title),
                            color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { dismiss() }) {
                            Text(context.getString(R.string.close_button), color = TextSub, fontSize = 12.sp)
                        }
                    }

                    // ── 1) GUI 표시 토글 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgItem)
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(context.getString(R.string.show_screen_buttons), color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(context.getString(R.string.show_hide_onscreen_controls), color = TextSub, fontSize = 11.sp)
                        }
                        Switch(
                            checked = guiOn,
                            onCheckedChange = {
                                guiOn = it
                                onToggleController()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Turtle,
                                uncheckedTrackColor = BgBorder
                            )
                        )
                    }

                    // ── 2) 핫바 터치 영역 크기 ──
                    //   화면 핫바 크기는 마인크래프트 옵션(GUI Scale)에서 바꾸고,
                    //   여기선 "핫바를 터치로 인식하는 영역"만 거기에 맞춘다.
                    //   Auto 가 안 맞으면 1~4 로 직접 맞춤(마인크래프트 GUI Scale 단위와 동일).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgItem)
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(context.getString(R.string.hotbar_touch_area_label), color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            context.getString(R.string.hotbar_area_size_desc),
                            color = TextSub, fontSize = 11.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val options = listOf(0 to "Auto", 1 to "1", 2 to "2", 3 to "3", 4 to "4")
                            options.forEach { (value, label) ->
                                val selected = hotbarScale == value
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Turtle else BgSurface)
                                        .border(
                                            1.dp,
                                            if (selected) Turtle else BgBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            hotbarScale = value
                                            mc?.setHotbarTouchScale(value)
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        color = if (selected) Color.White else TextSub,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    // ── 3) 온라인 LAN (Terracotta) ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgItem)
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .clickable { showTerracotta = true }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(context.getString(R.string.online_lan_multiplayer_icon), color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            context.getString(R.string.lan_open_then_join_hint),
                            color = TextSub, fontSize = 11.sp
                        )
                    }

                    Text(
                        context.getString(R.string.online_lan_open_to_lan_note),
                        color = TextSub, fontSize = 10.sp
                    )
                }
            }
        }
    }
}