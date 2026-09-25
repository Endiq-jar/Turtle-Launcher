package com.endiq.turtlelauncher.presentation.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.endiq.turtlelauncher.data.key.KeyButton
import com.endiq.turtlelauncher.data.key.KeyLayoutManager
import com.endiq.turtlelauncher.presentation.MinecraftActivity
import androidx.core.content.edit
import com.endiq.turtlelauncher.presentation.util.MinecraftActivityBridge
import kotlin.math.atan2
import kotlin.math.hypot

private const val GLFW_PRESS = 1
private const val GLFW_RELEASE = 0

// ────────────────────────────────────────────────────────────────────────
// KeyboardLayoutEditorScreen 의 상수/공식과 반드시 동일하게 유지.
// ────────────────────────────────────────────────────────────────────────
private const val BASE_BUTTON_UNIT = 52f
private const val TARGET_DP_PHONE = 48f
private const val TARGET_DP_TABLET = 76f
// Galaxy Z Flip 커버 화면(sw 약 300~320dp)처럼 일반 폰보다 훨씬 좁은 화면용.
// 접근성 최소 터치 타깃(40dp) 밑으로는 내려가지 않는다.
private const val TARGET_DP_COMPACT = 40f

// WASD 이동키 — 개별 버튼 대신 조이스틱 하나로 통합한다.
private const val KEY_W = 87
private const val KEY_A = 65
private const val KEY_S = 83
private const val KEY_D = 68
private val MOVE_KEYS = setOf(KEY_W, KEY_A, KEY_S, KEY_D)


// 폰/가로화면용 게임패드 프리셋 (GLFW 코드 → x, y 비율)
private val PHONE_LAYOUT_PRESETS: Map<Int, Pair<Float, Float>> = mapOf(
    87  to (0.14f to 0.7f),  // W
    65  to (0.06f to 0.88f),  // A
    83  to (0.14f to 0.88f),  // S
    68  to (0.22f to 0.88f),  // D
    256 to (0.06f to 0.10f),  // ESC
    -6  to (0.14f to 0.10f),  // keyboard toggle
    292 to (0.22f to 0.10f),  // F3
    294 to (0.3f to 0.10f),  // F5
    84  to (0.06f to 0.28f),  // T
    47  to (0.14f to 0.28f),  // /
    81  to (0.22f to 0.28f),  // Q
    69  to (0.92f to 0.7f),  // E (inventory)
    340 to (0.76f to 0.88f),  // shift = sneak
    341 to (0.84f to 0.88f),  // ctrl  = sprint
    32  to (0.92f to 0.88f),  // space = jump
    -7 to (0.84f to 0.7f),
)

class GameControllerView(context: Context) : View(context) {
    private var imeVisible: Boolean = false

    // 앉기(Shift, 340) 토글 상태 — true 면 Shift 가 눌린 상태로 유지된다.
    private var sneakToggled: Boolean = false

    // ESC 전용 모드: grab/IME 가 아닐 때(인벤토리/메뉴/타이틀) ESC 버튼만 표시·입력.
    private var escOnlyMode: Boolean = false

    fun setEscOnlyMode(enabled: Boolean) {
        if (escOnlyMode != enabled) {
            escOnlyMode = enabled
            if (enabled) resetJoystick()   // 메뉴/인벤토리 진입 시 이동 입력 정리
            invalidate()
        }
    }

    // ── 화면 컨트롤러 표시 토글(좌상단 🎮 버튼) ──
    //   controllerVisible == true  → 일반 버튼들 + 🎮 토글 모두 표시
    //   controllerVisible == false → 🎮 토글만 남기고 나머지 버튼 전부 숨김(입력도 막음)
    //   토글 자체는 항상 그려지고 항상 눌리므로, 꺼도 다시 켤 수 있다.
    //   기본 ON, SharedPreferences 에 저장돼 재실행에도 유지.
    private val controllerPrefs by lazy {
        context.getSharedPreferences("turtle_controller", Context.MODE_PRIVATE)
    }
    private var controllerVisible: Boolean =
        context.getSharedPreferences("turtle_controller", Context.MODE_PRIVATE)
            .getBoolean("force_show", true)

    private val toggleRect = RectF()   // 🎮 토글 버튼 영역(recalcRects 에서 계산)

    /** ☰ 메뉴 버튼을 탭했을 때 호출 — Activity 가 인게임 메뉴(Compose 오버레이)를 띄운다. */
    var onMenuClick: (() -> Unit)? = null

    /** 현재 컨트롤러(화면 버튼) 표시 여부 — 인게임 메뉴의 GUI 토글이 읽고 쓴다. */
    val isControllerVisible: Boolean get() = controllerVisible

    /** 인게임 메뉴에서 GUI(화면 버튼) 표시/숨김을 전환할 때 호출. */
    fun toggleControllerVisible() {
        setControllerVisible(!controllerVisible)
    }

    private fun setControllerVisible(visible: Boolean) {
        if (controllerVisible == visible) return
        controllerVisible = visible
        controllerPrefs.edit { putBoolean("force_show", visible) }
        // 켤 때 잔류 입력 정리(꺼져있던 동안 눌림 상태가 남지 않도록)
        if (!visible) releaseAllPressed()
        invalidate()
    }

    /** 현재 눌려있는 모든 버튼을 release 처리(토글 OFF 전환 시 안전 정리). */
    private fun releaseAllPressed() {
        // 조이스틱 이동키도 해제 (컨트롤러를 숨기면 스틱으로 끌 수 없으므로)
        resetJoystick()
        // 토글된 앉기(Shift)도 함께 해제 — 컨트롤러를 숨기면 버튼으로 끌 수 없으므로
        if (sneakToggled) {
            sneakToggled = false
            activity.sendKey(340, GLFW_RELEASE)
        }
        if (pressedButtons.isEmpty()) return
        pressedButtons.forEach { (id, _) ->
            buttons.find { it.id == id }?.let { handlePress(it.glfwCode, GLFW_RELEASE) }
        }
        pressedButtons.clear()
    }

    private val activity = context as MinecraftActivity
    private var buttons: List<KeyButton> = KeyLayoutManager.load(context)
    private var virtualMouseEnabled = false
    private val buttonRects = mutableMapOf<String, RectF>()

    /** Reloads the selected original Turtle preset without recreating the game Activity. */
    fun cycleControlPreset(): String {
        val (name, layout) = com.endiq.turtlelauncher.feature.turtle.ControlSwitcher.cycleToNext(context)
        if (layout.isNotEmpty()) {
            releaseAllPressed()
            buttons = layout
            recalcRects(width, height)
            invalidate()
        }
        return name
    }

    fun reloadControlLayout() {
        releaseAllPressed()
        buttons = KeyLayoutManager.load(context)
        recalcRects(width, height)
        invalidate()
    }
    private val pressedButtons = mutableMapOf<String, Int>()
    private val forwardedPids = mutableSetOf<Int>()  // 우리가 SurfaceView 로 떠넘긴 pointer
    private var surfaceViewCached: View? = null


    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(217, 26, 10, 20); style = Paint.Style.FILL
    }
    private val bgAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(217, 107, 0, 64); style = Paint.Style.FILL
    }
    private val bgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 156, 16, 96); style = Paint.Style.FILL
    }
    private val bgAccentPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 233, 30, 140); style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 122, 40, 85); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val borderAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 255, 107, 181); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val cornerRadius = 20f

    // ──────────────────────────────────────────────────────────────────
    // 이동(WASD) 조이스틱 — WASD 버튼 자리에 끌어서 조종하는 가상 스틱.
    //   base(바깥 원)는 WASD 키들의 중심에 배치되고, knob(손잡이)이 손가락을 따라온다.
    //   knob 방향을 8방위로 양자화해 W/A/S/D 키 누름으로 변환한다.
    // ──────────────────────────────────────────────────────────────────
    private var joystickEnabled = false
    private var joystickPointerId = -1
    private val joystickBase = PointF()
    private val joystickKnob = PointF()
    private var joystickRadius = 0f
    private var joystickKnobRadius = 0f
    private var joystickDeadZone = 0f
    private var activeMoveKeys: Set<Int> = emptySet()
    private val joystickArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // ──────────────────────────────────────────────────────────────────
    // FPS 표시 — 화면 중앙 상단. F3 디버그 HUD 대신 가벼운 카운터.
    //   값은 네이티브(egl_bridge.c::reportFpsToJava)가 실제 스왑 프레임 수로 계산해
    //   MinecraftActivityBridge → MinecraftActivity → updateFps(fps) 로 흘려보낸다.
    //   콜백이 아직 안 오면 currentFps = -1 → "-- FPS" 로 표시.
    // ──────────────────────────────────────────────────────────────────
    private var fpsVisible = true
    private var currentFps = -1
    private val fpsTextSizePx = resources.displayMetrics.density * 14f
    private val fpsTopMargin = resources.displayMetrics.density * 8f
    private val fpsBgRect = RectF()
    private val fpsBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
    }
    private val fpsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private fun isTabletDevice(): Boolean =
        resources.configuration.smallestScreenWidthDp >= 600

    // Z Flip 커버 화면처럼 일반 폰보다도 좁은 화면인지 (WindowSizeUtil.kt 의 Compact 기준과 동일: sw < 360dp).
    private fun isCompactDevice(): Boolean =
        resources.configuration.smallestScreenWidthDp < 360

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("TURTLE_LAUNCHER", "GameControllerView 크기: ${w}x${h}")
        recalcRects(w, h)
    }

    private fun rectsOverlap(buttons: List<KeyButton>, w: Int, h: Int, baseScale: Float): Boolean {
        if (buttons.size < 2) return false
        // ⚠️ 버튼마다 크기가 다를 수 있어서(에디터 리사이즈 지원) 균일한 half 대신
        //   각 버튼 자신의 width/height 로 판정해야 정확하다.
        val rs = Array(buttons.size) { i ->
            val halfW = (buttons[i].width * baseScale) / 2f
            val halfH = (buttons[i].height * baseScale) / 2f
            val cx = buttons[i].x * w
            val cy = buttons[i].y * h
            floatArrayOf(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        for (i in rs.indices) {
            for (j in i + 1 until rs.size) {
                val a = rs[i]; val b = rs[j]
                if (a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]) return true
            }
        }
        return false
    }

    /**
     * GLFW 코드별 프리셋으로 매핑하여 정리. 미인식 키는 화면 중앙쪽에 가로로 배치.
     */
    private fun applyPresetLayout(
        buttons: List<KeyButton>, w: Int, h: Int, drawSize: Float
    ): List<KeyButton> {
        if (buttons.isEmpty()) return buttons

        val recognized = mutableListOf<KeyButton>()
        val unrecognized = mutableListOf<KeyButton>()

        buttons.forEach { btn ->
            val preset = PHONE_LAYOUT_PRESETS[btn.glfwCode]
            if (preset != null) {
                recognized.add(btn.copy(
                    x = preset.first, y = preset.second,
                    width = BASE_BUTTON_UNIT, height = BASE_BUTTON_UNIT
                ))
            } else {
                unrecognized.add(btn)
            }
        }

        if (unrecognized.isEmpty() || w == 0) return recognized

        val gap = drawSize * 0.3f
        val totalW = unrecognized.size * drawSize + (unrecognized.size - 1) * gap
        val startX = (w - totalW) / 2f
        val extras = unrecognized.mapIndexed { i, btn ->
            val cx = startX + i * (drawSize + gap) + drawSize / 2f
            btn.copy(
                x = (cx / w).coerceIn(0.05f, 0.95f),
                y = 0.42f,
                width = BASE_BUTTON_UNIT, height = BASE_BUTTON_UNIT
            )
        }

        return recognized + extras
    }

    private fun surfaceView(): View? {
        val cur = surfaceViewCached
        if (cur != null && cur.isAttachedToWindow) return cur
        val sv = activity.window.decorView.findViewWithTag<View>("minecraft_surface")
        surfaceViewCached = sv
        return sv
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // 1) 새 pointer 분류 (버튼 위인지 vs 빈 영역인지)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                forwardedPids.clear()
                classifyNewPointer(event, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                classifyNewPointer(event, event.actionIndex)
            }
        }

        // 2) 이번 이벤트의 pointer 들을 ours/theirs 로 나눠서 각각 다른 view 로 보냄
        val theirEvent = filterEvent(event, keep = forwardedPids)
        val ourPids = (0 until event.pointerCount)
            .map { event.getPointerId(it) }
            .filter { it !in forwardedPids }
            .toSet()
        val ourEvent = filterEvent(event, keep = ourPids)

        if (ourEvent != null) {
            super.dispatchTouchEvent(ourEvent)   // → 우리 onTouchEvent
            ourEvent.recycle()
        }
        if (theirEvent != null) {
            surfaceView()?.dispatchTouchEvent(theirEvent)  // → SurfaceView 의 setOnTouchListener
            theirEvent.recycle()
        }

        // 3) pointer 끝나면 cleanup
        when (action) {
            MotionEvent.ACTION_POINTER_UP ->
                forwardedPids.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                forwardedPids.clear()
        }
        return true
    }

    private fun classifyNewPointer(event: MotionEvent, index: Int) {
        val pid = event.getPointerId(index)
        val px = event.getX(index)
        val py = event.getY(index)
        // 🎮 토글 영역, 일반 버튼, 또는 이동 조이스틱 위면 우리가 처리(keep), 아니면 surface 로 forward.
        val onUs = toggleRect.contains(px, py) || findButton(px, py) != null || isInJoystick(px, py)
        if (!onUs) {
            forwardedPids.add(pid)
        } else {
            // 이 포인터가 버튼/토글을 잡았다.
            //   단, 이미 surface 로 forward 중인 포인터(카메라 드래그용 손가락)가 있으면
            //   그건 정당한 멀티터치(이동+시점)이므로 건드리지 않는다.
            //   forward 중인 게 없을 때만(단일 탭으로 버튼을 누른 상황) surface 의
            //   잔류 드래그를 CANCEL 해 동시 입력/획 돎을 막는다.
            if (forwardedPids.isEmpty()) cancelSurfaceTouch()
        }
    }

    /** SurfaceView 로 ACTION_CANCEL 1회 전송 — 진행 중 드래그 강제 종료. */
    private fun cancelSurfaceTouch() {
        val sv = surfaceView() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { sv.dispatchTouchEvent(cancel) } catch (_: Exception) {}
        cancel.recycle()
    }

    /**
     * source 에서 keep 에 들어있는 pointer 만 남긴 새 MotionEvent 생성.
     * action 도 적절히 변환:
     *  - 액션 대상 pointer 가 keep 에 없으면 ACTION_MOVE 로 다운그레이드
     *  - keep 에 1개만 남으면 ACTION_POINTER_DOWN/UP → ACTION_DOWN/UP 으로 정규화
     */
    private fun filterEvent(source: MotionEvent, keep: Set<Int>): MotionEvent? {
        if (keep.isEmpty()) return null
        val keepIndices = (0 until source.pointerCount)
            .filter { source.getPointerId(it) in keep }
        if (keepIndices.isEmpty()) return null

        val sourceAction = source.actionMasked
        val sourceActionIdx = source.actionIndex
        val sourceActionPid = source.getPointerId(sourceActionIdx)

        val isDown = sourceAction == MotionEvent.ACTION_DOWN
                || sourceAction == MotionEvent.ACTION_POINTER_DOWN
        val isUp = sourceAction == MotionEvent.ACTION_UP
                || sourceAction == MotionEvent.ACTION_POINTER_UP

        val newAction: Int
        val newActionIdx: Int

        when {
            (isDown || isUp) && sourceActionPid !in keep -> {
                // 이번 액션의 주인공이 우리가 keep 하는 pointer 가 아님 → 그냥 MOVE
                newAction = MotionEvent.ACTION_MOVE
                newActionIdx = 0
            }
            isDown -> {
                val mapped = keepIndices.indexOf(sourceActionIdx)
                newAction =
                    if (keepIndices.size == 1) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_POINTER_DOWN
                newActionIdx = mapped
            }
            isUp -> {
                val mapped = keepIndices.indexOf(sourceActionIdx)
                newAction =
                    if (keepIndices.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP
                newActionIdx = mapped
            }
            else -> {
                newAction = sourceAction
                newActionIdx = 0
            }
        }

        val combinedAction = newAction or
                (newActionIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        val props = Array(keepIndices.size) { MotionEvent.PointerProperties() }
        val coords = Array(keepIndices.size) { MotionEvent.PointerCoords() }
        keepIndices.forEachIndexed { newI, origI ->
            source.getPointerProperties(origI, props[newI])
            source.getPointerCoords(origI, coords[newI])
        }

        return MotionEvent.obtain(
            source.downTime,
            source.eventTime,
            combinedAction,
            keepIndices.size,
            props,
            coords,
            source.metaState,
            source.buttonState,
            source.xPrecision,
            source.yPrecision,
            source.deviceId,
            source.edgeFlags,
            source.source,
            source.flags
        )
    }

    /**
     * density 기반 통합 스케일링 + 겹침 시 프리셋 레이아웃으로 자동 정리.
     */
    private fun recalcRects(w: Int, h: Int) {
        buttonRects.clear()

        val density = resources.displayMetrics.density
        val tablet = isTabletDevice()
        val compact = isCompactDevice()
        val targetDp = when {
            tablet  -> TARGET_DP_TABLET
            compact -> TARGET_DP_COMPACT
            else    -> TARGET_DP_PHONE
        }
        val baseScale = (targetDp * density) / BASE_BUTTON_UNIT
        val drawSize = BASE_BUTTON_UNIT * baseScale

        // 겹침 발견 시 프리셋 적용 + 저장
        if (rectsOverlap(buttons, w, h, baseScale)) {
            Log.d("TURTLE_LAUNCHER", "버튼 겹침 감지 — 프리셋 레이아웃으로 자동 정리")
            buttons = applyPresetLayout(buttons, w, h, drawSize)
            try { KeyLayoutManager.save(context, buttons) } catch (_: Exception) {}
        }

        val half = drawSize / 2f
        buttons.forEach { button ->
            // ⚠️ 에디터에서 리사이즈한 버튼은 width/height 가 서로 다를 수 있어서,
            //   전역 drawSize 대신 이 버튼 자신의 크기로 그려야 실제로 반영된다.
            val btnDrawW = button.width * baseScale
            val btnDrawH = button.height * baseScale
            val halfW = btnDrawW / 2f
            val halfH = btnDrawH / 2f
            val centerX = button.x * w
            val centerY = button.y * h
            // Z Flip 커버 화면처럼 좁은 화면에서 프리셋 비율이 그대로 적용되면
            // 버튼 절반이 화면 밖으로 밀려날 수 있어 화면 안쪽으로 clamp 한다.
            val left = (centerX - halfW).coerceIn(0f, (w - btnDrawW).coerceAtLeast(0f))
            val top = (centerY - halfH).coerceIn(0f, (h - btnDrawH).coerceAtLeast(0f))
            buttonRects[button.id] = RectF(left, top, left + btnDrawW, top + btnDrawH)
        }

        // ☰ 인게임 메뉴 버튼 — 화면 왼쪽 가장자리에 배치(기존엔 오른쪽이었음).
        //   ESC center 는 프리셋상 (0.06w, 0.10h). 왼쪽 가장자리 여백(startMargin)은 ESC 와 동일하게,
        //   세로는 ESC 바로 아래(겹치지 않도록 한 칸 + 약간의 간격)에 둔다.
        val escPreset = PHONE_LAYOUT_PRESETS[256] ?: (0.06f to 0.10f)
        val escCenterX = escPreset.first * w
        val escCenterY = escPreset.second * h
        val startMargin = escCenterX - (drawSize / 2f)      // 좌측 끝 ~ ESC 왼쪽 변
        val toggleLeftRaw = w - startMargin - drawSize          // endMargin = startMargin
        val toggleTopRaw = escCenterY - (drawSize / 2f)         // ESC 와 같은 y(중심 높이)       // ESC 아래로 한 칸 + 간격
        val toggleLeft = toggleLeftRaw.coerceIn(0f, (w - drawSize).coerceAtLeast(0f))
        val toggleTop = toggleTopRaw.coerceIn(0f, (h - drawSize).coerceAtLeast(0f))
        toggleRect.set(toggleLeft, toggleTop, toggleLeft + drawSize, toggleTop + drawSize)

        // ── 이동(WASD) 조이스틱 — x 는 이동키 중심, y 는 남은 앉기(Shift) 줄 기준으로 위로 띄움 ──
        val moveBtns = buttons.filter { it.glfwCode in MOVE_KEYS }
        joystickEnabled = moveBtns.isNotEmpty()
        if (joystickEnabled) {
            joystickRadius = drawSize * 1.2f          // 기존(1.6f)보다 작게
            joystickKnobRadius = drawSize * 0.55f
            joystickDeadZone = joystickRadius * 0.28f

            val cx = (moveBtns.sumOf { it.x.toDouble() } / moveBtns.size).toFloat() * w
            // 남은 앉기(Shift, 340) 버튼 y 를 바닥 기준선으로 삼아 스틱을 그 위로 띄운다.
            val sneakY = (buttons.find { it.glfwCode == 340 }?.y ?: 0.88f) * h
            val cy = sneakY - joystickRadius
            joystickBase.set(cx, cy)
            if (joystickPointerId == -1) joystickKnob.set(cx, cy)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // controllerVisible == false 면 일반 버튼은 그리지 않고 🎮 토글만 표시.
        if (controllerVisible) {
            buttons.forEach { button ->
                // 이동키(WASD)는 개별 버튼 대신 조이스틱으로 그린다.
                if (button.glfwCode in MOVE_KEYS) return@forEach
                // ESC 전용 모드면 ESC(256) + 키보드 토글(-6) 만 그린다(나머지 숨김).
                if (escOnlyMode && button.glfwCode !in setOf(256, -6, -8, -9)) return@forEach
                val rect = buttonRects[button.id] ?: return@forEach
                val isPressed = pressedButtons.containsKey(button.id)

                // ★ 전투 토글 버튼은 모드 상태에 따라 활성/비활성 표시
                val isCombatToggleActive = (button.glfwCode == -7 && activity.combatMode)
                // ★ 앉기(Shift) 토글도 상태에 따라 눌림 표시 유지
                val isSneakActive = (button.glfwCode == 340 && sneakToggled)
                val showPressed = isPressed || isSneakActive

                val fill = when {
                    isCombatToggleActive -> bgAccentPressedPaint   // 활성 = 진한 핑크
                    showPressed && button.isAccent -> bgAccentPressedPaint
                    showPressed -> bgPressedPaint
                    button.isAccent -> bgAccentPaint
                    else -> bgPaint
                }
                val border = if (button.isAccent || isCombatToggleActive) borderAccentPaint else borderPaint

                // Bundled Turtle layouts carry their original ARGB/opacity/radius metadata.
                // User-created layouts leave these nullable and keep the current Turtle styling.
                if (button.backgroundColor == null) {
                    fill.color = when {
                        fill === bgAccentPressedPaint -> if (button.isAccent || isCombatToggleActive) Color.argb(255, 233, 30, 140) else Color.argb(255, 156, 16, 96)
                        fill === bgAccentPaint -> Color.argb(217, 107, 0, 64)
                        fill === bgPressedPaint -> Color.argb(255, 156, 16, 96)
                        else -> Color.argb(217, 26, 10, 20)
                    }
                } else if (!showPressed && !button.isAccent) {
                    fill.color = applyOpacity(button.backgroundColor, button.opacity)
                }
                if (button.strokeColor == null) {
                    border.color = if (button.isAccent || isCombatToggleActive) Color.argb(178, 255, 107, 181) else Color.argb(178, 122, 40, 85)
                } else if (!button.isAccent && !isCombatToggleActive) {
                    border.color = applyOpacity(button.strokeColor, button.opacity)
                }
                fill.strokeWidth = 3f
                border.strokeWidth = (if (button.strokeColor != null) button.cornerRadius.coerceAtLeast(1f) else 3f)
                    .coerceIn(1f, 6f) * resources.displayMetrics.density / 2f
                val radius = if (button.backgroundColor != null)
                    button.cornerRadius * resources.displayMetrics.density
                else cornerRadius
                canvas.drawRoundRect(rect, radius, radius, fill)
                canvas.drawRoundRect(rect, radius, radius, border)

                // 라벨도 모드에 따라 바꾸기
                val labelText = when {
                    button.glfwCode == -7 -> if (activity.combatMode) "⚔️" else "🛠️"
                    else -> button.label
                }

                val fontSize = minOf(rect.width(), rect.height()) * 0.23f
                textPaint.textSize = fontSize
                canvas.drawText(
                    labelText,
                    rect.centerX(),
                    rect.centerY() + fontSize * 0.35f,
                    textPaint
                )
            }

            // 이동 조이스틱 (메뉴 모드가 아닐 때만)
            if (joystickEnabled && !escOnlyMode) drawJoystick(canvas)
        }

        // ── 🎮 컨트롤러 표시 토글 — 항상 그린다(꺼져 있어도 다시 켤 수 있도록) ──
        drawToggleButton(canvas)

        // ── FPS 표시 (중앙 상단) ──
        if (fpsVisible) drawFps(canvas)
    }

    /** 좌측 ☰ 인게임 메뉴 버튼 그리기. */
    private fun drawToggleButton(canvas: Canvas) {
        canvas.drawRoundRect(toggleRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(toggleRect, cornerRadius, cornerRadius, borderPaint)

        val fontSize = minOf(toggleRect.width(), toggleRect.height()) * 0.23f
        textPaint.textSize = fontSize
        canvas.drawText(
            "⚙️",
            toggleRect.centerX(),
            toggleRect.centerY() + fontSize * 0.35f,
            textPaint
        )
    }

    /** 이동 조이스틱(베이스 + 방향 화살표 + 손잡이) 그리기. */
    private fun drawJoystick(canvas: Canvas) {
        // 베이스 원
        canvas.drawCircle(joystickBase.x, joystickBase.y, joystickRadius, bgPaint)
        canvas.drawCircle(joystickBase.x, joystickBase.y, joystickRadius, borderPaint)

        // 방향 화살표(연하게)
        val arrowSize = joystickRadius * 0.24f
        joystickArrowPaint.textSize = arrowSize
        val r = joystickRadius * 0.72f
        canvas.drawText("▲", joystickBase.x, joystickBase.y - r + arrowSize * 0.35f, joystickArrowPaint)
        canvas.drawText("▼", joystickBase.x, joystickBase.y + r + arrowSize * 0.35f, joystickArrowPaint)
        canvas.drawText("◀", joystickBase.x - r, joystickBase.y + arrowSize * 0.35f, joystickArrowPaint)
        canvas.drawText("▶", joystickBase.x + r, joystickBase.y + arrowSize * 0.35f, joystickArrowPaint)

        // 손잡이 — 잡고 있으면 진한 핑크, 아니면 기본 강조색
        val active = joystickPointerId != -1
        canvas.drawCircle(joystickKnob.x, joystickKnob.y, joystickKnobRadius,
            if (active) bgPressedPaint else bgAccentPaint)
        canvas.drawCircle(joystickKnob.x, joystickKnob.y, joystickKnobRadius, borderAccentPaint)
    }

    /** 중앙 상단 FPS 표시 — 값에 따라 색이 바뀐다(드랍 시 빨강). */
    private fun drawFps(canvas: Canvas) {
        val text = if (currentFps < 0) "-- FPS" else "$currentFps FPS"
        fpsTextPaint.color = when {
            currentFps < 0 -> Color.argb(255, 200, 200, 200)
            currentFps >= 50 -> Color.argb(255, 120, 255, 140)   // 원활
            currentFps >= 30 -> Color.argb(255, 255, 210, 90)    // 주의
            else -> Color.argb(255, 255, 90, 90)                 // 드랍
        }
        fpsTextPaint.textSize = fpsTextSizePx
        val tw = fpsTextPaint.measureText(text)
        val padH = fpsTextSizePx * 0.6f
        val padV = fpsTextSizePx * 0.35f
        val cx = width / 2f
        val boxW = tw + padH * 2f
        val boxH = fpsTextSizePx + padV * 2f
        val left = cx - boxW / 2f
        fpsBgRect.set(left, fpsTopMargin, left + boxW, fpsTopMargin + boxH)
        canvas.drawRoundRect(fpsBgRect, cornerRadius * 0.6f, cornerRadius * 0.6f, fpsBgPaint)
        canvas.drawText(text, cx, fpsTopMargin + padV + fpsTextSizePx * 0.82f, fpsTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // ☰ 메뉴 버튼 먼저 처리 — 인게임 메뉴(Compose 오버레이)를 띄운다. 항상 최우선.
                if (toggleRect.contains(x, y)) {
                    onMenuClick?.invoke()
                    return true
                }
                // 이동 조이스틱 잡기 (isInJoystick 가 enabled/visible/escOnly 까지 검사)
                if (joystickPointerId == -1 && isInJoystick(x, y)) {
                    joystickPointerId = pointerId
                    updateJoystick(x, y)
                    invalidate()
                    return true
                }
                val button = findButton(x, y) ?: return false
                pressedButtons[button.id] = pointerId
                handlePress(button.glfwCode, GLFW_PRESS)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                var handled = false
                // 조이스틱 손가락이 떨어졌거나(또는 제스처 취소) → 손잡이 복귀 + 이동키 해제
                if (joystickPointerId != -1 && (pointerId == joystickPointerId || isCancel)) {
                    resetJoystick()
                    handled = true
                }
                val buttonId = pressedButtons.entries.find { it.value == pointerId }?.key
                if (buttonId != null) {
                    val button = buttons.find { it.id == buttonId }
                    pressedButtons.remove(buttonId)
                    if (button != null) handlePress(button.glfwCode, GLFW_RELEASE)
                    handled = true
                }
                if (handled) { invalidate(); return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                // 조이스틱 손가락은 드래그를 따라간다(스틱 전용 — 일반 버튼은 정적 유지).
                if (joystickPointerId != -1) {
                    val idx = event.findPointerIndex(joystickPointerId)
                    if (idx >= 0) {
                        updateJoystick(event.getX(idx), event.getY(idx))
                        invalidate()
                    }
                }
                // 스와이프로 일반 버튼이 눌리거나 풀리지 않게 한다.
                // 버튼은 DOWN 시점에 눌린 채로 고정되고, 그 손가락이 UP 될 때만 풀린다.
                return pressedButtons.isNotEmpty() || joystickPointerId != -1
            }
        }
        return false
    }

    private fun findButton(x: Float, y: Float): KeyButton? {
        // 컨트롤러가 꺼져 있으면 일반 버튼은 입력받지 않는다(🎮 토글만 별도 처리).
        if (!controllerVisible) return null
        buttons.forEach { button ->
            // 이동키(WASD)는 조이스틱이 처리하므로 일반 버튼 탭에서 제외.
            if (button.glfwCode in MOVE_KEYS) return@forEach
            // ESC 전용 모드면 ESC(256) + 키보드 토글(-6) 만 입력 받는다.
            if (escOnlyMode && button.glfwCode !in setOf(256, -6, -8, -9)) return@forEach
            val rect = buttonRects[button.id] ?: return@forEach
            if (rect.contains(x, y)) return button
        }
        return null
    }

    // ── 이동 조이스틱 헬퍼 ───────────────────────────────────────────────

    /** (x,y) 가 조이스틱 베이스 원 안인가 + 조이스틱이 활성/표시 상태인가. */
    private fun isInJoystick(x: Float, y: Float): Boolean {
        if (!joystickEnabled || !controllerVisible || escOnlyMode) return false
        val dx = x - joystickBase.x
        val dy = y - joystickBase.y
        return dx * dx + dy * dy <= joystickRadius * joystickRadius
    }

    /** 손가락 위치로 손잡이를 옮기고, 방향을 WASD 키 누름으로 변환. */
    private fun updateJoystick(x: Float, y: Float) {
        val dx = x - joystickBase.x
        val dy = y - joystickBase.y
        val dist = hypot(dx, dy)

        // 손잡이 위치 — 반경 안이면 그대로, 넘으면 원 둘레로 클램프
        if (dist > joystickRadius && dist > 0f) {
            val k = joystickRadius / dist
            joystickKnob.set(joystickBase.x + dx * k, joystickBase.y + dy * k)
        } else {
            joystickKnob.set(x, y)
        }

        // 방향 → 이동키 집합 (데드존 안이면 정지)
        val target = if (dist < joystickDeadZone) emptySet() else dirKeysFor(dx, dy)
        if (target != activeMoveKeys) {
            (activeMoveKeys - target).forEach { activity.sendKey(it, GLFW_RELEASE) }
            (target - activeMoveKeys).forEach { activity.sendKey(it, GLFW_PRESS) }
            activeMoveKeys = target
        }
    }

    /** 방향 벡터를 8방위로 양자화해 W/A/S/D 조합으로 변환. (화면 y 는 아래가 +) */
    private fun dirKeysFor(dx: Float, dy: Float): Set<Int> {
        var angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))  // E=0, N=90, W=180, S=270
        if (angle < 0) angle += 360.0
        return when ((((angle + 22.5) / 45.0).toInt()) % 8) {
            0 -> setOf(KEY_D)            // E
            1 -> setOf(KEY_W, KEY_D)     // NE
            2 -> setOf(KEY_W)            // N
            3 -> setOf(KEY_W, KEY_A)     // NW
            4 -> setOf(KEY_A)            // W
            5 -> setOf(KEY_S, KEY_A)     // SW
            6 -> setOf(KEY_S)            // S
            7 -> setOf(KEY_S, KEY_D)     // SE
            else -> emptySet()
        }
    }

    /** 손잡이 중앙 복귀 + 눌려있던 이동키 전부 해제 + 포인터 해제. */
    private fun resetJoystick() {
        if (activeMoveKeys.isNotEmpty()) {
            activeMoveKeys.forEach { activity.sendKey(it, GLFW_RELEASE) }
            activeMoveKeys = emptySet()
        }
        joystickPointerId = -1
        joystickKnob.set(joystickBase)
    }

    private fun applyOpacity(color: Int, opacity: Float): Int {
        val alpha = (((color ushr 24) * opacity.coerceIn(0f, 1f)).toInt()).coerceIn(0, 255)
        return (color and 0x00ffffff) or (alpha shl 24)
    }

    private fun handlePress(glfwCode: Int, action: Int) {
        when {
            // 앉기(Shift, 340)는 토글 — 누를 때마다 ON/OFF 전환, 손 떼는 동작은 무시
            glfwCode == 340 -> {
                if (action == GLFW_PRESS) {
                    sneakToggled = !sneakToggled
                    activity.sendKey(340, if (sneakToggled) GLFW_PRESS else GLFW_RELEASE)
                    invalidate()  // 버튼 활성 표시 갱신
                }
            }
            glfwCode >= 0 -> activity.sendKey(glfwCode, action)
            glfwCode == -1 -> activity.sendMouseButton(0, action)
            glfwCode == -2 -> activity.sendMouseButton(1, action)
            glfwCode == -3 -> activity.sendMouseButton(2, action)
            // Original Turtle special controls: GUI toggles controls, MENU opens the launcher
            // overlay, and VIRTUALMOUSE switches the touch-pointer mode. Ordinary surface
            // touches are already forwarded to Minecraft, so the latter is an explicit state
            // switch rather than a second competing touch dispatcher.
            glfwCode == -8 && action == GLFW_PRESS -> {
                toggleControllerVisible()
            }
            glfwCode == -9 && action == GLFW_PRESS -> {
                onMenuClick?.invoke()
            }
            glfwCode == -5 && action == GLFW_PRESS -> {
                virtualMouseEnabled = !virtualMouseEnabled
                Log.d("TURTLE_LAUNCHER", "Original Turtle virtual mouse: $virtualMouseEnabled")
                invalidate()
            }
            // glfwCode == -6 분기 전체 교체
            glfwCode == -6 && action == GLFW_PRESS -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                val surfaceView = activity.window.decorView
                    .findViewWithTag<android.view.View>("minecraft_surface") ?: return

                // 매번 포커스 다시 잡기 — 한 번 잃으면 showSoftInput 이 무시됨
                surfaceView.isFocusable = true
                surfaceView.isFocusableInTouchMode = true
                surfaceView.requestFocus()

                if (imeVisible) {
                    imm.hideSoftInputFromWindow(surfaceView.windowToken, 0)
                    imeVisible = false
                } else {
                    // SHOW_IMPLICIT 말고 0 — 사용자가 명시적으로 누른 거니까 강제로
                    surfaceView.post {
                        imm.showSoftInput(surfaceView, 0)
                    }
                    imeVisible = true
                }
            }
            glfwCode == -7 && action == GLFW_PRESS -> {
                activity.combatMode = !activity.combatMode
                Log.d("TURTLE_LAUNCHER", "전투 모드: ${if (activity.combatMode) "ON" else "OFF"}")
                invalidate()  // 버튼 색상 갱신용
            }
        }
    }

    fun setImeVisibleExternal(visible: Boolean) {
        if (imeVisible != visible) {
            imeVisible = visible
        }
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
    }

    // 네이티브가 계산해 보내준 실제 게임 FPS. MinecraftActivity 가 호출.
    fun updateFps(fps: Int) {
        currentFps = fps
        if (fpsVisible) postInvalidateOnAnimation()   // 어느 스레드서 와도 안전
    }

    private val hotbarKey: String
        get() = "hotbar_slot_${MinecraftActivityBridge.currentWorldName}"

    private var currentHotbarSlot: Int
        get() = activity.getSharedPreferences("TURTLE_LAUNCHER", Context.MODE_PRIVATE).getInt(hotbarKey, 0)
        set(value) {
            activity.getSharedPreferences("TURTLE_LAUNCHER", Context.MODE_PRIVATE).edit { putInt(hotbarKey, value) }
        }
}