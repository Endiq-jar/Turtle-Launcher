package kr.co.donghyun.turtlelauncher.presentation.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GlfwKeys.fromAndroid] 검증.
 *
 * 이 매핑은 "Android 와 GLFW 의 키코드가 각 구간에서 나란히 연속"이라는 전제로
 * 오프셋 산술을 쓴다. 그 전제가 깨지면(또는 상수를 잘못 적으면) 게임에서 특정
 * 키만 조용히 안 먹는 형태로 나타나 원인 추적이 어렵다. 여기서 못 박아둔다.
 *
 * KeyEvent 상수는 컴파일 타임에 인라인되는 static final int 라
 * Robolectric 없이 순수 JVM 테스트로 돌아간다.
 */
class GlfwKeysTest {

    @Test
    fun `알파벳 26개가 GLFW 65~90 으로 연속 매핑된다`() {
        for (i in 0 until 26) {
            val expected = 65 + i   // GLFW_KEY_A = 65
            assertEquals(
                "letter index $i",
                expected,
                GlfwKeys.fromAndroid(KeyEvent.KEYCODE_A + i),
            )
        }
        assertEquals(65, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_A))
        assertEquals(90, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_Z))
    }

    @Test
    fun `숫자 0-9 가 GLFW 48~57 로 매핑된다`() {
        for (i in 0 until 10) {
            assertEquals("digit $i", 48 + i, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_0 + i))
        }
    }

    @Test
    fun `F1-F12 가 GLFW 290~301 로 매핑된다`() {
        for (i in 0 until 12) {
            assertEquals("F${i + 1}", 290 + i, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_F1 + i))
        }
    }

    @Test
    fun `넘패드 0-9 가 GLFW 320~329 로 매핑된다`() {
        for (i in 0 until 10) {
            assertEquals("KP$i", 320 + i, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_NUMPAD_0 + i))
        }
    }

    @Test
    fun `Android DEL 은 Backspace, FORWARD_DEL 이 Delete 다`() {
        // 이름이 헷갈려서 반대로 넣기 쉬운 자리 — 뒤집히면 채팅에서 지우기가 안 먹는다.
        assertEquals(GlfwKeys.KEY_BACKSPACE, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_DEL))
        assertEquals(GlfwKeys.KEY_DELETE, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_FORWARD_DEL))
    }

    @Test
    fun `수식키는 좌우를 구분한다`() {
        assertEquals(GlfwKeys.KEY_LEFT_SHIFT, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(GlfwKeys.KEY_RIGHT_SHIFT, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(GlfwKeys.KEY_LEFT_CONTROL, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(GlfwKeys.KEY_RIGHT_CONTROL, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(GlfwKeys.KEY_LEFT_ALT, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(GlfwKeys.KEY_RIGHT_ALT, GlfwKeys.fromAndroid(KeyEvent.KEYCODE_ALT_RIGHT))
    }

    @Test
    fun `마인크래프트가 실제로 쓰는 키들이 빠짐없이 매핑된다`() {
        // 예전 부분 테이블에서 누락돼 안 먹던 것들 — 회귀 방지용.
        val mustMap = mapOf(
            "F3 디버그" to KeyEvent.KEYCODE_F3,
            "F5 시점" to KeyEvent.KEYCODE_F5,
            "F11 전체화면" to KeyEvent.KEYCODE_F11,
            "M 미니맵" to KeyEvent.KEYCODE_M,
            "U (JEI)" to KeyEvent.KEYCODE_U,
            "B" to KeyEvent.KEYCODE_B,
            "C" to KeyEvent.KEYCODE_C,
            "G" to KeyEvent.KEYCODE_G,
            "K" to KeyEvent.KEYCODE_K,
            "L" to KeyEvent.KEYCODE_L,
            "X" to KeyEvent.KEYCODE_X,
            "Z" to KeyEvent.KEYCODE_Z,
            "0" to KeyEvent.KEYCODE_0,
            "ESC" to KeyEvent.KEYCODE_ESCAPE,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "SPACE" to KeyEvent.KEYCODE_SPACE,
        )
        for ((label, code) in mustMap) {
            assertEquals("$label 이 매핑돼야 한다", true, GlfwKeys.fromAndroid(code) != null)
        }
    }

    @Test
    fun `매핑 없는 키는 null 을 돌려준다`() {
        assertNull(GlfwKeys.fromAndroid(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(GlfwKeys.fromAndroid(KeyEvent.KEYCODE_POWER))
        assertNull(GlfwKeys.fromAndroid(KeyEvent.KEYCODE_HOME))
    }
}
