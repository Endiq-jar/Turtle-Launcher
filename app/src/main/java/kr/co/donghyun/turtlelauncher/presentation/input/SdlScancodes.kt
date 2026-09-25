package kr.co.donghyun.turtlelauncher.presentation.input

/**
 * GLFW 키코드 → SDL 스캔코드(= USB HID usage).
 *
 * 마인크래프트 26.3 은 키를 **SDL 스캔코드**로 읽는다(InputConstants.KEY_W = 26).
 * 화면 버튼·키보드·게임패드는 전부 GLFW 코드로 들어오므로 여기서 한 번 바꾼다.
 * 그 전 버전(GLFW 경로)은 이 표를 지나가지 않는다.
 *
 * 대응이 없으면 0 — 네이티브가 0 을 무시한다.
 */
internal fun glfwToSdlScancode(glfw: Int): Int = when (glfw) {
    in 65..90   -> 4 + (glfw - 65)           // A..Z  → HID 4..29
    in 49..57   -> 30 + (glfw - 49)          // 1..9  → HID 30..38
    48          -> 39                        // 0
    in 290..301 -> 58 + (glfw - 290)         // F1..F12 → HID 58..69
    in 320..329 -> if (glfw == 320) 98 else 89 + (glfw - 321)  // 키패드 0..9
    else        -> SPECIALS[glfw] ?: 0
}

private val SPECIALS = mapOf(
    32 to 44,    // Space
    39 to 52,    // '
    44 to 54,    // ,
    45 to 45,    // -
    46 to 55,    // .
    47 to 56,    // /
    59 to 51,    // ;
    61 to 46,    // =
    91 to 47,    // [
    92 to 49,    // \
    93 to 48,    // ]
    96 to 53,    // `
    256 to 41,   // Escape
    257 to 40,   // Enter
    258 to 43,   // Tab
    259 to 42,   // Backspace
    260 to 73,   // Insert
    261 to 76,   // Delete
    262 to 79,   // →
    263 to 80,   // ←
    264 to 81,   // ↓
    265 to 82,   // ↑
    266 to 75,   // PageUp
    267 to 78,   // PageDown
    268 to 74,   // Home
    269 to 77,   // End
    280 to 57,   // CapsLock
    281 to 71,   // ScrollLock
    282 to 83,   // NumLock
    283 to 70,   // PrintScreen
    284 to 72,   // Pause
    330 to 99,   // 키패드 .
    331 to 84,   // 키패드 /
    332 to 85,   // 키패드 *
    333 to 86,   // 키패드 -
    334 to 87,   // 키패드 +
    335 to 88,   // 키패드 Enter
    340 to 225,  // LeftShift
    341 to 224,  // LeftControl
    342 to 226,  // LeftAlt
    343 to 227,  // LeftSuper
    344 to 229,  // RightShift
    345 to 228,  // RightControl
    346 to 230,  // RightAlt
    347 to 231,  // RightSuper
    348 to 101,  // Menu
)
