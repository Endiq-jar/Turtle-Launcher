package kr.co.donghyun.flamelauncher.presentation.ui.screen

import kr.co.donghyun.flamelauncher.R
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kr.co.donghyun.flamelauncher.data.key.KeyButton
import kr.co.donghyun.flamelauncher.presentation.ui.theme.*
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet
import kr.co.donghyun.flamelauncher.presentation.util.window.isCompact
import java.util.UUID

// ────────────────────────────────────────────────────────────────────────
// 공통 스케일 상수 (GameControllerView 와 반드시 동일하게 유지)
// ────────────────────────────────────────────────────────────────────────
private const val BASE_BUTTON_UNIT = 52f
private const val TARGET_DP_PHONE = 48f
private const val TARGET_DP_TABLET = 76f
// Galaxy Z Flip 커버 화면 등 아주 좁은 화면용 (GameControllerView 와 동일 값 유지)
private const val TARGET_DP_COMPACT = 40f

// 파워포인트 스타일 정렬 가이드 — 다른 버튼과 x 또는 y 좌표가 이 값(캔버스 비율) 이내로
// 가까워지면 그 좌표에 스냅 + 가이드 라인 표시 + 햅틱.
private const val ALIGNMENT_SNAP_THRESHOLD = 0.012f

// 리사이즈 가능한 버튼 크기 범위 ("unit" 단위, BASE_BUTTON_UNIT 과 같은 스케일)
private const val MIN_BUTTON_SIZE = 28f
private const val MAX_BUTTON_SIZE = 96f

/** 드래그 중 표시할 정렬 가이드 라인 — 캔버스 비율(0~1) 좌표 기준. null 이면 해당 축 가이드 없음. */
data class AlignmentGuides(val x: Float? = null, val y: Float? = null)

private fun calcBaseScale(tablet: Boolean, compact: Boolean, density: Float): Float {
    val targetDp = when {
        tablet  -> TARGET_DP_TABLET
        compact -> TARGET_DP_COMPACT
        else    -> TARGET_DP_PHONE
    }
    return (targetDp * density) / BASE_BUTTON_UNIT
}

// ────────────────────────────────────────────────────────────────────────
// 폰/가로화면용 게임패드 프리셋 (GLFW 코드 → (x, y) 비율)
// 마인크래프트 컨트롤러 표준 배치를 재현
// ────────────────────────────────────────────────────────────────────────
private val PHONE_LAYOUT_PRESETS: Map<Int, Pair<Float, Float>> = mapOf(
    // 이동 WASD (좌측 하단, 십자형)
    87  to (0.14f to 0.7f),  // W
    65  to (0.06f to 0.88f),  // A
    83  to (0.14f to 0.88f),  // S
    68  to (0.22f to 0.88f),  // D

    // 기능키 (좌측 상단)
    256 to (0.06f to 0.10f),  // ESC
    -6  to (0.14f to 0.10f),  // 키보드 토글
    292 to (0.22f to 0.10f),  // F3
    294 to (0.30f to 0.10f),  // F5

    // 채팅/커맨드/드롭 (좌측 중단)
    84  to (0.06f to 0.28f),  // T
    47  to (0.14f to 0.28f),  // /
    81  to (0.22f to 0.28f),  // Q

    // 우측 인벤토리 / 슬롯
    69  to (0.92f to 0.7f),  // E (인벤토리)
    -7 to (0.84f to 0.7f),

    // 점프/슬쩍/달리기 (우측 하단)
    340 to (0.76f to 0.88f),  // shift = sneak
    341 to (0.84f to 0.88f),  // ctrl  = sprint
    32  to (0.92f to 0.88f),  // space = jump
)

/** 저장 데이터의 width/height 가 0 이하이거나 비정상인 경우에만 표준값으로 보정 (예전 빌드 호환).
 *  ⚠️ 예전엔 "표준값과 다르면 무조건 표준값으로" 였는데, 이러면 사용자가 리사이즈해서
 *  커스텀 크기로 저장한 버튼도 편집기를 열 때마다 원래 크기로 되돌아가는 버그가 있었다. */
private fun normalizeButtons(buttons: List<KeyButton>): List<KeyButton> =
    buttons.map {
        val validWidth = if (it.width > 0f && !it.width.isNaN()) it.width else BASE_BUTTON_UNIT
        val validHeight = if (it.height > 0f && !it.height.isNaN()) it.height else BASE_BUTTON_UNIT
        if (validWidth == it.width && validHeight == it.height) it
        else it.copy(width = validWidth, height = validHeight)
    }

/** AABB 충돌 검사 — 임의의 두 버튼이 겹치면 true */
private fun hasOverlap(
    buttons: List<KeyButton>,
    canvasSize: IntSize,
    tablet: Boolean,
    compact: Boolean,
    density: Float
): Boolean {
    if (buttons.size < 2 || canvasSize.width == 0 || canvasSize.height == 0) return false

    val baseScale = calcBaseScale(tablet, compact, density)
    val w = canvasSize.width.toFloat()
    val h = canvasSize.height.toFloat()

    // ⚠️ 버튼마다 크기가 다를 수 있어서(리사이즈 지원) 균일한 half 대신
    //   각 버튼 자신의 width/height 로 충돌 판정해야 정확하다.
    val rects = Array(buttons.size) { i ->
        val halfW = (buttons[i].width * baseScale) / 2f
        val halfH = (buttons[i].height * baseScale) / 2f
        val cx = buttons[i].x * w
        val cy = buttons[i].y * h
        floatArrayOf(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            if (a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]) return true
        }
    }
    return false
}

/**
 * GLFW 코드별 프리셋 좌표로 매핑하여 정리한다.
 * 프리셋에 없는 커스텀 키들은 상단 중앙에 가로로 배치.
 */
private fun applyPresetLayout(
    buttons: List<KeyButton>,
    canvasSize: IntSize,
    tablet: Boolean,
    compact: Boolean,
    density: Float
): List<KeyButton> {
    if (buttons.isEmpty()) return buttons

    val recognized = mutableListOf<KeyButton>()
    val unrecognized = mutableListOf<KeyButton>()

    buttons.forEach { btn ->
        val preset = PHONE_LAYOUT_PRESETS[btn.glfwCode]
        if (preset != null) {
            recognized.add(btn.copy(
                x = preset.first,
                y = preset.second,
                width = BASE_BUTTON_UNIT,
                height = BASE_BUTTON_UNIT
            ))
        } else {
            unrecognized.add(btn)
        }
    }

    if (unrecognized.isEmpty() || canvasSize.width == 0) {
        return recognized
    }

    // 미인식 키는 상단 중앙(y=0.10)에 가로 한 줄로 배치
    val baseScale = calcBaseScale(tablet, compact, density)
    val buttonPx = BASE_BUTTON_UNIT * baseScale
    val gap = buttonPx * 0.3f
    val canvasW = canvasSize.width.toFloat()
    val totalW = unrecognized.size * buttonPx + (unrecognized.size - 1) * gap
    val startX = (canvasW - totalW) / 2f

    val extras = unrecognized.mapIndexed { i, btn ->
        val cx = startX + i * (buttonPx + gap) + buttonPx / 2f
        btn.copy(
            x = (cx / canvasW).coerceIn(0.05f, 0.95f),
            y = 0.42f,                                     // 미인식 키는 중앙쪽에
            width = BASE_BUTTON_UNIT,
            height = BASE_BUTTON_UNIT
        )
    }

    return recognized + extras
}

object GlfwKeysAll {
    data class KeyInfo(val label: String, val glfwCode: Int)

    val ALL_KEYS = listOf(
        KeyInfo("A", 65), KeyInfo("B", 66), KeyInfo("C", 67), KeyInfo("D", 68),
        KeyInfo("E", 69), KeyInfo("F", 70), KeyInfo("G", 71), KeyInfo("H", 72),
        KeyInfo("I", 73), KeyInfo("J", 74), KeyInfo("K", 75), KeyInfo("L", 76),
        KeyInfo("M", 77), KeyInfo("N", 78), KeyInfo("O", 79), KeyInfo("P", 80),
        KeyInfo("Q", 81), KeyInfo("R", 82), KeyInfo("S", 83), KeyInfo("T", 84),
        KeyInfo("U", 85), KeyInfo("V", 86), KeyInfo("W", 87), KeyInfo("X", 88),
        KeyInfo("Y", 89), KeyInfo("Z", 90),
        KeyInfo("0", 48), KeyInfo("1", 49), KeyInfo("2", 50), KeyInfo("3", 51),
        KeyInfo("4", 52), KeyInfo("5", 53), KeyInfo("6", 54), KeyInfo("7", 55),
        KeyInfo("8", 56), KeyInfo("9", 57),
        KeyInfo("ESC", 256), KeyInfo("↵", 257), KeyInfo("Tab", 258), KeyInfo("Space", 32),
        KeyInfo("BS", 259), KeyInfo("Del", 261), KeyInfo("Ins", 260),
        KeyInfo("↑", 265), KeyInfo("↓", 264), KeyInfo("←", 263), KeyInfo("→", 262),
        KeyInfo("⇧L", 340), KeyInfo("⌃L", 341), KeyInfo("AltL", 342),
        KeyInfo("F1", 290), KeyInfo("F2", 291), KeyInfo("F3", 292), KeyInfo("F4", 293),
        KeyInfo("F5", 294), KeyInfo("F6", 295), KeyInfo("F7", 296), KeyInfo("F8", 297),
        KeyInfo("F9", 298), KeyInfo("F10", 299), KeyInfo("F11", 300), KeyInfo("F12", 301)
    )
}

@Composable
fun KeyboardLayoutEditorScreen(
    onBack: () -> Unit,
    initialButtons: List<KeyButton>,
    onSave: (List<KeyButton>) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var buttons by remember { mutableStateOf(normalizeButtons(initialButtons)) }
    var selectedButtonId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var guides by remember { mutableStateOf(AlignmentGuides()) }
    val tablet = isTablet()
    val compact = isCompact()

    // 캔버스 측정 후 겹침 감지 → 프리셋 레이아웃으로 자동 정리
    LaunchedEffect(canvasSize.width, canvasSize.height) {
        if (canvasSize.width > 0 && canvasSize.height > 0 &&
            hasOverlap(buttons, canvasSize, tablet, compact, density.density)
        ) {
            buttons = applyPresetLayout(buttons, canvasSize, tablet, compact, density.density)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(0.dp))
                .padding(horizontal = if (tablet) 16.dp else if (compact) 6.dp else 10.dp, vertical = if (tablet) 12.dp else if (compact) 6.dp else 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(context.getString(R.string.cancel_button), color = TextSub, fontSize = if (tablet) 16.sp else if (compact) 11.sp else 13.sp)
            }
            Text(
                context.getString(R.string.edit_virtual_keypad),
                color = TextMain,
                fontSize = if (tablet) 18.sp else if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp)
            )
            Row {
                Button(
                    onClick = { onSave(buttons); onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = Flame),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
                ) {
                    Text(context.getString(R.string.apply_button), color = Color.White, fontSize = if (tablet) 13.sp else if (compact) 10.sp else 11.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                .onGloballyPositioned { canvasSize = it.size }
        ) {
            buttons.forEach { btn ->
                DraggableKeyButton(
                    button = btn,
                    otherButtons = buttons.filter { it.id != btn.id },
                    canvasSize = canvasSize,
                    isSelected = selectedButtonId == btn.id,
                    onSelect = { selectedButtonId = btn.id },
                    onMove = { nx, ny ->
                        buttons = buttons.map {
                            if (it.id == btn.id) it.copy(x = nx, y = ny) else it
                        }
                    },
                    onDragEnd = { guides = AlignmentGuides() },
                    onGuidesChanged = { guides = it },
                    haptic = haptic,
                )
            }

            // 파워포인트 스타일 정렬 가이드 라인 — 버튼들 위에 그려져야 하니 forEach 뒤에 위치.
            if (guides.x != null || guides.y != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    guides.x?.let { gx ->
                        val px = gx * size.width
                        drawLine(
                            color = Flame,
                            start = Offset(px, 0f),
                            end = Offset(px, size.height),
                            strokeWidth = 2f,
                        )
                    }
                    guides.y?.let { gy ->
                        val py = gy * size.height
                        drawLine(
                            color = Flame,
                            start = Offset(0f, py),
                            end = Offset(size.width, py),
                            strokeWidth = 2f,
                        )
                    }
                }
            }

            // ⚠️ 선택된 버튼의 삭제(×)/리사이즈 스테퍼는 DraggableKeyButton 안에서
            //   그리지 않고 여기, 이 Box 안의 다른 모든 요소(버튼 전체·정렬 가이드·
            //   "모드 추가" 버튼)가 다 그려진 뒤 "진짜 맨 마지막"에 따로 그린다.
            //   전에는 "모드 추가" 버튼이 이 오버레이보다 나중에 배치돼 있어서
            //   그 버튼이 오버레이를 덮어버리는 문제가 있었다(Compose 는 같은 부모
            //   안에서 그려진 순서대로 겹침 — 나중에 추가된 요소가 위에 옴).
            val selected = buttons.find { it.id == selectedButtonId }

            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Flame),
                contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(if (compact) 10.dp else 16.dp)
                    .height(if (tablet) 44.dp else if (compact) 32.dp else 36.dp)
            ) {
                Text(context.getString(R.string.add_key_button), fontSize = if (tablet) 14.sp else if (compact) 10.sp else 11.sp, fontWeight = FontWeight.Bold)
            }

            if (selected != null) {
                SelectedButtonOverlay(
                    button = selected,
                    canvasSize = canvasSize,
                    onResize = { newSize ->
                        buttons = buttons.map {
                            if (it.id == selected.id) it.copy(width = newSize, height = newSize) else it
                        }
                    },
                    onDelete = {
                        buttons = buttons.filter { it.id != selected.id }
                        selectedButtonId = null
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            onDismiss = { showAddDialog = false },
            compact = compact,
            onAdd = { keyInfo ->
                buttons = buttons + KeyButton(
                    id = UUID.randomUUID().toString(),
                    label = keyInfo.label,
                    glfwCode = keyInfo.glfwCode,
                    x = 0.5f,
                    y = 0.5f,
                    width = BASE_BUTTON_UNIT,
                    height = BASE_BUTTON_UNIT,
                    isAccent = keyInfo.glfwCode == 32
                )
                showAddDialog = false
            },
            tablet = tablet
        )
    }
}

@Composable
fun DraggableKeyButton(
    button: KeyButton,
    otherButtons: List<KeyButton>,
    canvasSize: IntSize,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onGuidesChanged: (AlignmentGuides) -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    val density = LocalDensity.current
    val tablet = isTablet()
    val compact = isCompact()
    val viewWidth = canvasSize.width.toFloat()
    val viewHeight = canvasSize.height.toFloat()

    val baseScale = calcBaseScale(tablet, compact, density.density)

    // ⚠️ pointerInput 코루틴은 button.id 가 같으면 재시작되지 않는데, 그 안에서
    //   otherButtons/button/각종 콜백을 "그때 그 시점" 값으로 캡처해버리면(stale
    //   closure), 재조합 때마다 이 값들이 바뀌어도 제스처 코드는 예전 값을 계속
    //   참조하게 된다 — 이게 "드래그하면 이전 위치로 되돌아간다"는 버그의 진짜
    //   원인이었다(정렬 스냅 판정에 오래된 otherButtons 좌표가 쓰이거나, 콜백이
    //   갱신되기 전의 상태를 참조). rememberUpdatedState 로 감싸서 제스처 코드가
    //   항상 "최신" 값을 보게 만든다.
    val currentButton by rememberUpdatedState(button)
    val currentOtherButtons by rememberUpdatedState(otherButtons)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnGuidesChanged by rememberUpdatedState(onGuidesChanged)

    // ⚠️ 버튼마다 크기가 다를 수 있어서(리사이즈 지원) 전역 BASE_BUTTON_UNIT 이 아니라
    //   이 버튼 자신의 width/height 를 써야 한다.
    val drawWidthPx = button.width * baseScale
    val drawHeightPx = button.height * baseScale
    val btnWidthDp = with(density) { drawWidthPx.toDp() }
    val btnHeightDp = with(density) { drawHeightPx.toDp() }

    // Z Flip 커버 화면처럼 좁은 캔버스에서 버튼이 편집 영역 밖으로 밀려나지 않도록 clamp.
    val rawLeftPx = button.x * viewWidth - (drawWidthPx / 2f)
    val rawTopPx = button.y * viewHeight - (drawHeightPx / 2f)
    val clampedLeftPx = if (viewWidth > 0f) rawLeftPx.coerceIn(0f, (viewWidth - drawWidthPx).coerceAtLeast(0f)) else rawLeftPx
    val clampedTopPx = if (viewHeight > 0f) rawTopPx.coerceIn(0f, (viewHeight - drawHeightPx).coerceAtLeast(0f)) else rawTopPx

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { clampedLeftPx.toDp() },
                y = with(density) { clampedTopPx.toDp() }
            )
            .size(width = btnWidthDp, height = btnHeightDp)
            // ⚠️ 버튼들이 촘촘히 배치돼 있으면, 나중에 그려지는 옆 버튼이 먼저 그려진
            //   버튼의 삭제(×)/스테퍼 같은 "튀어나온" 장식을 덮어버려서 안 보이는
            //   문제가 있었다(Compose 는 같은 부모 안에서 그려진 순서대로 겹친다).
            //   선택된 버튼만 zIndex 를 높여서 항상 다른 버튼들 위에 그려지게 한다.
            .zIndex(if (isSelected) 10f else 0f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Flame.copy(alpha = 0.4f) else BgSurface)
            .border(2.dp, if (isSelected) Flame else BgBorder, RoundedCornerShape(8.dp))
            .pointerInput(button.id, viewWidth, viewHeight) {
                // ⚠️ 이 블록 자체는 button.id/viewWidth/viewHeight 가 바뀔 때만 재시작되므로,
                //   그 밖의 값(otherButtons, 콜백들)은 위에서 만든 rememberUpdatedState 를
                //   통해서만 참조해야 항상 최신값을 본다.
                var wasAlignedX = false
                var wasAlignedY = false
                var hapticFiredThisGesture = false   // 한 번의 드래그(터치~뗌) 동안 햅틱 최대 1회.

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnSelect()
                    wasAlignedX = false
                    wasAlignedY = false
                    hapticFiredThisGesture = false
                    var currentX = currentButton.x
                    var currentY = currentButton.y
                    var lastPos = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val dragEvent = event.changes.firstOrNull { it.pressed }
                        if (dragEvent == null) {
                            currentOnDragEnd()
                            break
                        }
                        val currentPos = dragEvent.position
                        val dx = (currentPos.x - lastPos.x) / viewWidth
                        val dy = (currentPos.y - lastPos.y) / viewHeight
                        if (dx != 0f || dy != 0f) {
                            var nx = (currentX + dx).coerceIn(0.05f, 0.95f)
                            var ny = (currentY + dy).coerceIn(0.05f, 0.95f)

                            // 다른 버튼들과 x/y 가 가까우면 스냅 + 가이드 표시(항상 최신 otherButtons 기준).
                            val alignedOtherX = currentOtherButtons.firstOrNull { kotlin.math.abs(it.x - nx) < ALIGNMENT_SNAP_THRESHOLD }
                            val alignedOtherY = currentOtherButtons.firstOrNull { kotlin.math.abs(it.y - ny) < ALIGNMENT_SNAP_THRESHOLD }
                            if (alignedOtherX != null) nx = alignedOtherX.x
                            if (alignedOtherY != null) ny = alignedOtherY.y

                            currentOnGuidesChanged(AlignmentGuides(x = alignedOtherX?.x, y = alignedOtherY?.y))

                            val isAlignedXNow = alignedOtherX != null
                            val isAlignedYNow = alignedOtherY != null
                            val enteredAlignment = (isAlignedXNow && !wasAlignedX) || (isAlignedYNow && !wasAlignedY)
                            if (enteredAlignment && !hapticFiredThisGesture) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                hapticFiredThisGesture = true
                            }
                            wasAlignedX = isAlignedXNow
                            wasAlignedY = isAlignedYNow

                            currentX = nx
                            currentY = ny
                            currentOnMove(nx, ny)
                            dragEvent.consume()
                        }
                        lastPos = currentPos
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val calculatedFontSize = kotlin.math.min(drawWidthPx, drawHeightPx) * 0.22f
        Text(
            text = button.label,
            color = TextMain,
            fontSize = with(density) { calculatedFontSize.toSp() },
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
/**
 * 선택된 버튼의 삭제(×)/리사이즈 스테퍼 — buttons.forEach 전체와 별개로,
 * 항상 가장 마지막에 그려지는 독립 레이어. 버튼이 아무리 촘촘히 배치돼
 * 있어도 다른 버튼에 가려지지 않는다(같은 부모 안에서 이 컴포저블 자체가
 * 리스트 상 가장 나중에 추가되므로 항상 최상단에 그려짐).
 */
fun SelectedButtonOverlay(
    button: KeyButton,
    canvasSize: IntSize,
    onResize: (Float) -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val tablet = isTablet()
    val compact = isCompact()
    val viewWidth = canvasSize.width.toFloat()
    val viewHeight = canvasSize.height.toFloat()
    val baseScale = calcBaseScale(tablet, compact, density.density)

    val drawWidthPx = button.width * baseScale
    val drawHeightPx = button.height * baseScale

    val rawLeftPx = button.x * viewWidth - (drawWidthPx / 2f)
    val rawTopPx = button.y * viewHeight - (drawHeightPx / 2f)
    val clampedLeftPx = if (viewWidth > 0f) rawLeftPx.coerceIn(0f, (viewWidth - drawWidthPx).coerceAtLeast(0f)) else rawLeftPx
    val clampedTopPx = if (viewHeight > 0f) rawTopPx.coerceIn(0f, (viewHeight - drawHeightPx).coerceAtLeast(0f)) else rawTopPx

    // ⚠️ 버튼 크기(작을 수도 있는)에 맞춘 Box 안에 스테퍼를 넣으면, 그 Box 의 폭이
    //   좁을 때 스테퍼 내용물(버튼 2개+숫자)이 들어갈 공간이 부족해서 같이 눌려
    //   찌그러져 보이는 문제가 있었다 — Compose 는 기본적으로 자식을 부모가 정한
    //   크기 안에 맞추려 하기 때문. 그래서 여기선 버튼 크기의 컨테이너를 아예 쓰지
    //   않고, 삭제 버튼/스테퍼 각각을 캔버스 전체 기준의 절대 좌표로 따로 배치한다
    //   — 이러면 버튼이 아무리 작아져도 두 장식물은 항상 자기 본연의 크기를 유지한다.
    val deleteXDp = with(density) { (clampedLeftPx + drawWidthPx - 10.dp.toPx()).toDp() }
    val deleteYDp = with(density) { (clampedTopPx - 10.dp.toPx()).toDp() }
    val buttonCenterXDp = with(density) { (clampedLeftPx + drawWidthPx / 2f).toDp() }
    val stepperYDp = with(density) { (clampedTopPx + drawHeightPx + 20.dp.toPx()).toDp() }

    // 삭제(×) — 우측 상단. 20dp 크기 + 10dp 오프셋 + 흰 테두리로 어떤 배경
    //   위에서도 명확히 도드라지게. 버튼 크기와 무관하게 항상 20dp 고정.
    Box(
        modifier = Modifier
            .offset(x = deleteXDp, y = deleteYDp)
            .size(20.dp)
            .background(Color.Red, CircleShape)
            .border(1.5.dp, Color.White, CircleShape)
            .clickable { onDelete() },
        contentAlignment = Alignment.Center
    ) {
        Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }

    // 크기 조절 스테퍼("- 숫자 +") — 선택된 버튼 바로 아래 중앙에 뜨고, 버튼이
    //   아무리 작아져도 항상 자기 크기를 유지(wrapContentWidth 로 폭을 스스로 결정).
    Row(
        modifier = Modifier
            .offset(x = buttonCenterXDp, y = stepperYDp)
            .wrapContentWidth(unbounded = true)
            .offset(x = (-46).dp)  // Row 폭(약 92dp)의 절반만큼 왼쪽으로 이동해 중앙 정렬.
            .clip(RoundedCornerShape(999.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SizeStepperButton(label = "−") {
            onResize((button.width - 4f).coerceIn(MIN_BUTTON_SIZE, MAX_BUTTON_SIZE))
        }
        Text(
            text = button.width.toInt().toString(),
            color = TextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp).padding(horizontal = 4.dp),
        )
        SizeStepperButton(label = "+") {
            onResize((button.width + 4f).coerceIn(MIN_BUTTON_SIZE, MAX_BUTTON_SIZE))
        }
    }
}

@Composable
private fun SizeStepperButton(label: String, onClick: () -> Unit) {
    // ⚠️ "−"(마이너스)와 "+"(플러스) 두 버튼은 완전히 동일한 크기/스타일을 써야
    //   한다 — 글자 모양 때문에 시각적으로 다르게 느껴지지 않도록 텍스트 크기·
    //   정렬도 명시적으로 통일한다.
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Flame)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun AddKeyDialog(onDismiss: () -> Unit, onAdd: (GlfwKeysAll.KeyInfo) -> Unit, tablet: Boolean, compact: Boolean = false) {
    val context = LocalContext.current
    val columns = if (tablet) 6 else if (compact) 3 else 4
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (tablet) 0.8f else 0.95f)
                .heightIn(max = if (tablet) 450.dp else if (compact) 300.dp else 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                .padding(if (compact) 10.dp else 14.dp)
        ) {
            Column {
                Text(context.getString(R.string.select_key_to_add), color = TextMain, fontSize = if (tablet) 16.sp else if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val chunks = GlfwKeysAll.ALL_KEYS.chunked(columns)
                    items(chunks) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { keyInfo ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(if (tablet) 44.dp else if (compact) 32.dp else 34.dp)
                                        .background(BgDark, RoundedCornerShape(6.dp))
                                        .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                                        .clickable { onAdd(keyInfo) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        keyInfo.label,
                                        color = TextMain,
                                        fontSize = if (tablet) 12.sp else if (compact) 9.sp else 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    )
                                }
                            }
                            repeat(columns - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}