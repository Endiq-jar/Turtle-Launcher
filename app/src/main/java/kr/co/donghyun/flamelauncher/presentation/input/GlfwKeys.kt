package kr.co.donghyun.flamelauncher.presentation.input

import android.view.KeyEvent

/**
 * GLFW 키/마우스 상수 + Android KeyEvent → GLFW 키코드 매핑.
 *
 * 마인크래프트(LWJGL/GLFW)는 키를 GLFW 키코드로 받는다. 기존 매핑 테이블은
 * W/A/S/D/E/Q/F/R/T + 숫자 1~9 + 일부 기호만 있어서 알파벳 26개 중 9개만 전달됐다.
 * 그 결과 채팅 글자는 (문자 콜백으로) 들어가도 **키 바인딩이 대부분 안 먹었다** —
 * 인벤토리 정렬(R), 미니맵(M), JEI(U), F3 디버그, F5 시점전환, F11 전체화면 등.
 *
 * 여기서는 연속 구간(A~Z, 0~9, F1~F12, 넘패드)을 산술로 처리하고 나머지만 표로 둔다.
 * 표를 100줄 나열하는 것보다 짧고, 오타로 한 글자가 빠질 여지도 없다.
 */
object GlfwKeys {

    // ── GLFW 액션 ──
    const val RELEASE = 0
    const val PRESS = 1

    // ── GLFW 수식키 비트 ──
    const val MOD_SHIFT = 0x0001
    const val MOD_CONTROL = 0x0002
    const val MOD_ALT = 0x0004
    const val MOD_SUPER = 0x0008

    // ── GLFW 마우스 버튼 ──
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    // ── GLFW 키코드 (자주 쓰는 것만 이름 부여) ──
    const val KEY_SPACE = 32
    const val KEY_0 = 48
    const val KEY_A = 65
    const val KEY_ESCAPE = 256
    const val KEY_ENTER = 257
    const val KEY_TAB = 258
    const val KEY_BACKSPACE = 259
    const val KEY_INSERT = 260
    const val KEY_DELETE = 261
    const val KEY_RIGHT = 262
    const val KEY_LEFT = 263
    const val KEY_DOWN = 264
    const val KEY_UP = 265
    const val KEY_PAGE_UP = 266
    const val KEY_PAGE_DOWN = 267
    const val KEY_HOME = 268
    const val KEY_END = 269
    const val KEY_CAPS_LOCK = 280
    const val KEY_SCROLL_LOCK = 281
    const val KEY_NUM_LOCK = 282
    const val KEY_PRINT_SCREEN = 283
    const val KEY_PAUSE = 284
    const val KEY_F1 = 290
    const val KEY_KP_0 = 320
    const val KEY_KP_DECIMAL = 330
    const val KEY_KP_DIVIDE = 331
    const val KEY_KP_MULTIPLY = 332
    const val KEY_KP_SUBTRACT = 333
    const val KEY_KP_ADD = 334
    const val KEY_KP_ENTER = 335
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341
    const val KEY_LEFT_ALT = 342
    const val KEY_LEFT_SUPER = 343
    const val KEY_RIGHT_SHIFT = 344
    const val KEY_RIGHT_CONTROL = 345
    const val KEY_RIGHT_ALT = 346
    const val KEY_RIGHT_SUPER = 347
    const val KEY_MENU = 348

    /**
     * Android keyCode → GLFW 키코드. 매핑 없으면 null.
     *
     * 연속 구간은 Android/GLFW 양쪽 다 연속이라 오프셋 산술로 처리한다:
     *   KEYCODE_A(29)..KEYCODE_Z(54)             → 65..90
     *   KEYCODE_0(7)..KEYCODE_9(16)              → 48..57
     *   KEYCODE_F1(131)..KEYCODE_F12(142)        → 290..301
     *   KEYCODE_NUMPAD_0(144)..NUMPAD_9(153)     → 320..329
     */
    fun fromAndroid(keyCode: Int): Int? {
        when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                return KEY_A + (keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                return KEY_0 + (keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                return KEY_F1 + (keyCode - KeyEvent.KEYCODE_F1)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                return KEY_KP_0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        }
        return when (keyCode) {
            // 기호 (GLFW 는 US 배열 기준 ASCII 값을 그대로 씀)
            KeyEvent.KEYCODE_SPACE -> KEY_SPACE
            KeyEvent.KEYCODE_APOSTROPHE -> 39
            KeyEvent.KEYCODE_COMMA -> 44
            KeyEvent.KEYCODE_MINUS -> 45
            KeyEvent.KEYCODE_PERIOD -> 46
            KeyEvent.KEYCODE_SLASH -> 47
            KeyEvent.KEYCODE_SEMICOLON -> 59
            KeyEvent.KEYCODE_EQUALS -> 61
            KeyEvent.KEYCODE_LEFT_BRACKET -> 91
            KeyEvent.KEYCODE_BACKSLASH -> 92
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 93
            KeyEvent.KEYCODE_GRAVE -> 96

            // 편집/이동
            KeyEvent.KEYCODE_ESCAPE -> KEY_ESCAPE
            KeyEvent.KEYCODE_ENTER -> KEY_ENTER
            KeyEvent.KEYCODE_NUMPAD_ENTER -> KEY_KP_ENTER
            KeyEvent.KEYCODE_TAB -> KEY_TAB
            KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE       // Android DEL = Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE  // Android FORWARD_DEL = Delete
            KeyEvent.KEYCODE_INSERT -> KEY_INSERT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
            KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
            KeyEvent.KEYCODE_PAGE_UP -> KEY_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> KEY_PAGE_DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> KEY_HOME
            KeyEvent.KEYCODE_MOVE_END -> KEY_END

            // 토글/시스템
            KeyEvent.KEYCODE_CAPS_LOCK -> KEY_CAPS_LOCK
            KeyEvent.KEYCODE_SCROLL_LOCK -> KEY_SCROLL_LOCK
            KeyEvent.KEYCODE_NUM_LOCK -> KEY_NUM_LOCK
            KeyEvent.KEYCODE_SYSRQ -> KEY_PRINT_SCREEN
            KeyEvent.KEYCODE_BREAK -> KEY_PAUSE
            KeyEvent.KEYCODE_MENU -> KEY_MENU

            // 수식키 (좌/우 구분)
            KeyEvent.KEYCODE_SHIFT_LEFT -> KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KEY_RIGHT_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT -> KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_CTRL_RIGHT -> KEY_RIGHT_CONTROL
            KeyEvent.KEYCODE_ALT_LEFT -> KEY_LEFT_ALT
            KeyEvent.KEYCODE_ALT_RIGHT -> KEY_RIGHT_ALT
            KeyEvent.KEYCODE_META_LEFT -> KEY_LEFT_SUPER
            KeyEvent.KEYCODE_META_RIGHT -> KEY_RIGHT_SUPER

            // 넘패드 연산자
            KeyEvent.KEYCODE_NUMPAD_DOT -> KEY_KP_DECIMAL
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KEY_KP_DIVIDE
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KEY_KP_MULTIPLY
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KEY_KP_SUBTRACT
            KeyEvent.KEYCODE_NUMPAD_ADD -> KEY_KP_ADD

            else -> null
        }
    }

    /** Android KeyEvent 의 metaState → GLFW mods 비트. */
    fun modsFrom(event: KeyEvent): Int =
        (if (event.isShiftPressed) MOD_SHIFT else 0) or
            (if (event.isCtrlPressed) MOD_CONTROL else 0) or
            (if (event.isAltPressed) MOD_ALT else 0) or
            (if (event.isMetaPressed) MOD_SUPER else 0)
}
