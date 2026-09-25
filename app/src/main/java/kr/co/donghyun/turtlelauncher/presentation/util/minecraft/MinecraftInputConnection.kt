package kr.co.donghyun.turtlelauncher.presentation.util.minecraft

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import kr.co.donghyun.turtlelauncher.presentation.MinecraftActivity

class MinecraftInputConnection(
    targetView: View,
    private val activity: MinecraftActivity,
) : BaseInputConnection(targetView, /*fullEditor=*/ true) {

    // 조합 중인 텍스트. 한글 IME 가 매 자모마다 호출.
    private var pendingComposition: String = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        Log.d("PING_IME", "commitText: '$text'")
        // 조합 완료된 텍스트는 그대로 송신
        pendingComposition = ""
        text?.forEach { c ->
            // ★ 일부 IME(예: Gboard, 삼성 키보드)는 Enter 를 sendKeyEvent(KEYCODE_ENTER) 와
            //   commitText("...\n") 양쪽으로 동시에 보낸다. Enter 는 sendKeyEvent 쪽에서
            //   이미 GLFW 257(Backspace 처럼 down/up)로 처리되므로, 여기서 개행문자를 문자로
            //   또 보내면 Enter 로 트리거되는 동작(채팅 전송 등)이 두 번 일어난다.
            //   → 개행/캐리지리턴은 문자로 보내지 않고 스킵.
            if (c != '\n' && c != '\r') sendCharToMc(c)
        }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        Log.d("PING_IME", "setComposingText: '$text' (pending='$pendingComposition')")

        val newText = text?.toString().orEmpty()
        val oldText = pendingComposition

        // 한글 조합 처리 전략:
        //   "ㄱ" → "가" → "강" 식으로 조합이 진행되는데,
        //   매번 마지막 글자만 채팅창에 반영되어야 함.
        //   즉 이전 조합문자를 backspace 로 지우고 새 조합문자를 commit 해야 자연스럽다.
        //
        // 그러나 MC 의 EditBox 는 IME 조합 개념이 없어서, 단순히 "글자 송신" 만 지원.
        // 따라서 다음과 같이 한다:
        //   1) 이전 조합 글자가 있다면 backspace 송신 (= 키 KEYCODE_DEL)
        //   2) 새 조합 텍스트의 마지막 글자를 nativeSendCharMods 로 송신

        if (oldText.isNotEmpty()) {
            // 이전 조합 문자 지우기 - oldText 길이만큼 backspace
            repeat(oldText.length) {
                activity.sendKey(259, 1)  // GLFW Backspace down
                activity.sendKey(259, 0)  // up
            }
        }

        if (newText.isNotEmpty()) {
            // 새 조합 글자 송신
            newText.forEach { sendCharToMc(it) }
        }

        pendingComposition = newText
        return true
    }

    override fun finishComposingText(): Boolean {
        Log.d("PING_IME", "finishComposingText (pending='$pendingComposition')")
        // 조합이 확정되면 pendingComposition 는 그대로 commit 된 상태로 간주
        // 이미 setComposingText 에서 송신됐으므로 추가 작업 없음.
        pendingComposition = ""
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val glfwAction = if (isDown) 1 else 0
        val glfwKey = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL          -> 259  // Backspace
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 257
            KeyEvent.KEYCODE_DPAD_LEFT    -> 263
            KeyEvent.KEYCODE_DPAD_RIGHT   -> 262
            KeyEvent.KEYCODE_DPAD_UP      -> 265
            KeyEvent.KEYCODE_DPAD_DOWN    -> 264
            KeyEvent.KEYCODE_FORWARD_DEL  -> 261
            else -> return super.sendKeyEvent(event)
        }
        activity.sendKey(glfwKey, glfwAction)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        Log.d("PING_IME", "deleteSurroundingText: before=$beforeLength after=$afterLength")
        repeat(beforeLength) {
            activity.sendKey(259, 1)
            activity.sendKey(259, 0)
        }
        repeat(afterLength) {
            activity.sendKey(261, 1)
            activity.sendKey(261, 0)
        }
        return true
    }

    /** MC 의 nativeSendCharMods 로 단일 문자 송신 */
    private fun sendCharToMc(c: Char) {
        try {
            val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
            // Char 콜백 (1.12 이하)
            cb.getMethod("nativeSendChar", Char::class.java).invoke(null, c)
            // CharMods 콜백 (1.13+) — 핵심
            cb.getMethod("nativeSendCharMods", Char::class.java, Int::class.java)
                .invoke(null, c, 0)
        } catch (e: Exception) {
            Log.e("PING_IME", "sendCharToMc 실패", e)
        }
    }
}