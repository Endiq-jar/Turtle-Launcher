package com.endiq.turtlelauncher.presentation.input

import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * 게임패드/조이스틱 → 마인크래프트 입력 변환.
 *
 * 마인크래프트 자바판은 컨트롤러를 네이티브로 지원하지 않는다(GLFW 조이스틱 API 를
 * 바닐라가 쓰지 않음). 그래서 "실제 마인크래프트 입력과 최대한 비슷하게" 만드는
 * 유일한 방법은 패드 입력을 **키보드/마우스 이벤트로 변환**하는 것이고, 콘솔판·
 * Controllable 모드도 결국 같은 일을 한다.
 *
 * 매핑은 콘솔판(Bedrock) 기본 배치를 따른다:
 *   왼쪽 스틱 → WASD (임계값 넘으면 키 누름 — MC 이동은 원래 디지털이라 이게 맞다)
 *   오른쪽 스틱 → 마우스 시점 (아날로그, 프레임마다 델타 누적)
 *   RT → 좌클릭(공격/채굴)   LT → 우클릭(사용/설치)
 *   A → Space(점프)  B → Shift(웅크리기)  X → Q(버리기)  Y → E(인벤토리)
 *   LB/RB → 핫바 이전/다음(휠)  L3 → Ctrl(달리기)  R3 → 휠클릭(블록 선택)
 *   Start → ESC   Select → Tab   D-pad → 방향키(메뉴 이동)
 *
 * ponytail: 매핑 고정. 사용자 리매핑 UI 는 실제 요청이 오면 추가 —
 *           지금 넣으면 쓰지도 않을 설정 화면·저장 포맷·마이그레이션이 따라붙는다.
 */
class GamepadHandler(
    private val sendKey: (glfwKey: Int, action: Int) -> Unit,
    private val sendMouseButton: (button: Int, action: Int) -> Unit,
    private val sendScroll: (xOffset: Float, yOffset: Float) -> Unit,
    private val moveCursorBy: (dx: Float, dy: Float) -> Unit,
) {

    companion object {
        /** 스틱이 이 값을 넘어야 입력으로 친다(기기가 flat 값을 보고하면 그쪽 우선). */
        private const val DEFAULT_DEADZONE = 0.15f

        /** 트리거가 이 값을 넘으면 눌린 것으로 본다. */
        private const val TRIGGER_THRESHOLD = 0.5f

        /** 왼쪽 스틱이 이 값을 넘으면 해당 방향 이동키를 누른다. */
        private const val MOVE_THRESHOLD = 0.5f

        /** 오른쪽 스틱 최대 기울기에서의 시점 회전 속도(픽셀/초). */
        private const val LOOK_SPEED = 900f
    }

    /** 이벤트가 게임패드/조이스틱에서 온 것인지. */
    fun isGamepadEvent(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    // ── 아날로그 상태 (Choreographer 펌프가 매 프레임 읽음) ──
    @Volatile private var lookX = 0f
    @Volatile private var lookY = 0f

    // ── 에지 검출용 이전 상태 (같은 키를 계속 재전송하지 않도록) ──
    private var moveForward = false
    private var moveBack = false
    private var moveLeft = false
    private var moveRight = false
    private var leftTriggerDown = false
    private var rightTriggerDown = false
    private var hatLeft = false
    private var hatRight = false
    private var hatUp = false
    private var hatDown = false

    private var running = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
            lastFrameNanos = frameTimeNanos

            // dt 상한 — 앱이 잠깐 멈췄다 돌아왔을 때 시점이 확 튀는 것 방지.
            val step = dt.coerceIn(0f, 1f / 20f)
            if (step > 0f && (lookX != 0f || lookY != 0f)) {
                moveCursorBy(lookX * LOOK_SPEED * step, lookY * LOOK_SPEED * step)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 시점 회전 펌프 시작. 메인 스레드에서 호출할 것. */
    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** 펌프 정지 + 눌려 있던 키 전부 해제(정지 중 키가 눌린 채로 남는 것 방지). */
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lookX = 0f
        lookY = 0f
        releaseAllHeld()
    }

    /**
     * 현재 눌린 것으로 기록된 입력을 전부 뗌 처리한다.
     * 이걸 안 하면 패드를 뽑거나 앱이 백그라운드로 갈 때 W 가 눌린 채로 남아
     * 캐릭터가 계속 앞으로 걸어간다.
     */
    private fun releaseAllHeld() {
        moveForward = edge(moveForward, false) { sendKey(glfw('W'), GlfwKeys.RELEASE) }
        moveBack = edge(moveBack, false) { sendKey(glfw('S'), GlfwKeys.RELEASE) }
        moveLeft = edge(moveLeft, false) { sendKey(glfw('A'), GlfwKeys.RELEASE) }
        moveRight = edge(moveRight, false) { sendKey(glfw('D'), GlfwKeys.RELEASE) }
        hatLeft = edge(hatLeft, false) { sendKey(GlfwKeys.KEY_LEFT, GlfwKeys.RELEASE) }
        hatRight = edge(hatRight, false) { sendKey(GlfwKeys.KEY_RIGHT, GlfwKeys.RELEASE) }
        hatUp = edge(hatUp, false) { sendKey(GlfwKeys.KEY_UP, GlfwKeys.RELEASE) }
        hatDown = edge(hatDown, false) { sendKey(GlfwKeys.KEY_DOWN, GlfwKeys.RELEASE) }
        leftTriggerDown =
            edge(leftTriggerDown, false) { sendMouseButton(GlfwKeys.MOUSE_RIGHT, GlfwKeys.RELEASE) }
        rightTriggerDown =
            edge(rightTriggerDown, false) { sendMouseButton(GlfwKeys.MOUSE_LEFT, GlfwKeys.RELEASE) }
    }

    // ── 버튼 ─────────────────────────────────────────────────────────

    /** @return 이 이벤트를 소비했으면 true. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isGamepadEvent(event.source)) return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> GlfwKeys.PRESS
            KeyEvent.ACTION_UP -> GlfwKeys.RELEASE
            else -> return false
        }
        // 자동 반복은 무시 — MC 는 누름/뗌만 필요하고, 반복은 게임이 알아서 한다.
        if (event.repeatCount > 0) return true

        val down = action == GlfwKeys.PRESS
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> sendKey(GlfwKeys.KEY_SPACE, action)
            KeyEvent.KEYCODE_BUTTON_B -> sendKey(GlfwKeys.KEY_LEFT_SHIFT, action)
            KeyEvent.KEYCODE_BUTTON_X -> sendKey(glfw('Q'), action)
            KeyEvent.KEYCODE_BUTTON_Y -> sendKey(glfw('E'), action)

            KeyEvent.KEYCODE_BUTTON_L1 -> if (down) sendScroll(0f, 1f)   // 핫바 이전
            KeyEvent.KEYCODE_BUTTON_R1 -> if (down) sendScroll(0f, -1f)  // 핫바 다음

            KeyEvent.KEYCODE_BUTTON_THUMBL -> sendKey(GlfwKeys.KEY_LEFT_CONTROL, action)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> sendMouseButton(GlfwKeys.MOUSE_MIDDLE, action)

            KeyEvent.KEYCODE_BUTTON_START -> sendKey(GlfwKeys.KEY_ESCAPE, action)
            KeyEvent.KEYCODE_BUTTON_SELECT -> sendKey(GlfwKeys.KEY_TAB, action)

            // 일부 패드는 트리거를 축이 아니라 버튼으로 보낸다.
            KeyEvent.KEYCODE_BUTTON_L2 -> sendMouseButton(GlfwKeys.MOUSE_RIGHT, action)
            KeyEvent.KEYCODE_BUTTON_R2 -> sendMouseButton(GlfwKeys.MOUSE_LEFT, action)

            // D-pad 가 키로 오는 경우(축으로 오는 경우는 handleMotionEvent 에서 처리)
            KeyEvent.KEYCODE_DPAD_UP -> sendKey(GlfwKeys.KEY_UP, action)
            KeyEvent.KEYCODE_DPAD_DOWN -> sendKey(GlfwKeys.KEY_DOWN, action)
            KeyEvent.KEYCODE_DPAD_LEFT -> sendKey(GlfwKeys.KEY_LEFT, action)
            KeyEvent.KEYCODE_DPAD_RIGHT -> sendKey(GlfwKeys.KEY_RIGHT, action)
            KeyEvent.KEYCODE_DPAD_CENTER -> sendKey(GlfwKeys.KEY_ENTER, action)

            else -> return false
        }
        return true
    }

    // ── 축(스틱/트리거/D-pad) ────────────────────────────────────────

    /** @return 이 이벤트를 소비했으면 true. */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!isGamepadEvent(event.source)) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false

        val dev = event.device
        val dz = deadzoneOf(dev, MotionEvent.AXIS_X)

        // 왼쪽 스틱 (+ 일부 패드가 D-pad 를 HAT 축으로 보냄)
        val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X), dz)
        val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y), dz)
        updateMovement(lx, ly)

        // 오른쪽 스틱 — 기기에 따라 Z/RZ 또는 RX/RY 로 온다.
        var rx = event.getAxisValue(MotionEvent.AXIS_Z)
        var ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (rx == 0f && ry == 0f) {
            rx = event.getAxisValue(MotionEvent.AXIS_RX)
            ry = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        val rdz = deadzoneOf(dev, MotionEvent.AXIS_Z)
        // 제곱 응답 곡선 — 작은 기울기에서 정밀 조준, 끝까지 밀면 빠르게.
        lookX = curve(applyDeadzone(rx, rdz))
        lookY = curve(applyDeadzone(ry, rdz))

        // 트리거 — AXIS_LTRIGGER/RTRIGGER 가 없으면 BRAKE/GAS 로 오는 기기가 있다.
        val lt = max(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        val rt = max(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )
        leftTriggerDown = edge(leftTriggerDown, lt > TRIGGER_THRESHOLD) { down ->
            sendMouseButton(GlfwKeys.MOUSE_RIGHT, if (down) GlfwKeys.PRESS else GlfwKeys.RELEASE)
        }
        rightTriggerDown = edge(rightTriggerDown, rt > TRIGGER_THRESHOLD) { down ->
            sendMouseButton(GlfwKeys.MOUSE_LEFT, if (down) GlfwKeys.PRESS else GlfwKeys.RELEASE)
        }

        // D-pad 를 HAT 축으로 보내는 패드 대응
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatLeft = edge(hatLeft, hx < -0.5f) { d -> sendKey(GlfwKeys.KEY_LEFT, actionOf(d)) }
        hatRight = edge(hatRight, hx > 0.5f) { d -> sendKey(GlfwKeys.KEY_RIGHT, actionOf(d)) }
        hatUp = edge(hatUp, hy < -0.5f) { d -> sendKey(GlfwKeys.KEY_UP, actionOf(d)) }
        hatDown = edge(hatDown, hy > 0.5f) { d -> sendKey(GlfwKeys.KEY_DOWN, actionOf(d)) }

        return true
    }

    /** 왼쪽 스틱 → WASD 누름/뗌. 임계값 기준 디지털 변환(MC 이동은 원래 디지털). */
    private fun updateMovement(lx: Float, ly: Float) {
        moveForward = edge(moveForward, ly < -MOVE_THRESHOLD) { d -> sendKey(glfw('W'), actionOf(d)) }
        moveBack = edge(moveBack, ly > MOVE_THRESHOLD) { d -> sendKey(glfw('S'), actionOf(d)) }
        moveLeft = edge(moveLeft, lx < -MOVE_THRESHOLD) { d -> sendKey(glfw('A'), actionOf(d)) }
        moveRight = edge(moveRight, lx > MOVE_THRESHOLD) { d -> sendKey(glfw('D'), actionOf(d)) }
    }

    // ── 유틸 ─────────────────────────────────────────────────────────

    /** 상태가 바뀐 경우에만 [onChange] 를 부르고 새 상태를 돌려준다. */
    private inline fun edge(prev: Boolean, now: Boolean, onChange: (Boolean) -> Unit): Boolean {
        if (prev != now) onChange(now)
        return now
    }

    private fun actionOf(down: Boolean) = if (down) GlfwKeys.PRESS else GlfwKeys.RELEASE

    /** 'W' → GLFW_KEY_W. GLFW 알파벳 키코드는 대문자 ASCII 와 같다. */
    private fun glfw(c: Char): Int = c.code

    private fun deadzoneOf(dev: InputDevice?, axis: Int): Float {
        val flat = try {
            dev?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)?.flat ?: 0f
        } catch (_: Throwable) {
            0f
        }
        return max(flat, DEFAULT_DEADZONE)
    }

    /** 데드존 밖 구간을 0..1 로 다시 펼친다(데드존 경계에서 값이 튀지 않도록). */
    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        val a = abs(value)
        if (a <= deadzone) return 0f
        val scaled = (a - deadzone) / (1f - deadzone)
        return if (value < 0) -scaled else scaled
    }

    private fun curve(v: Float): Float = v * abs(v)
}
