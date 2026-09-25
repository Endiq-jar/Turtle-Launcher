package kr.co.donghyun.turtlelauncher.presentation.input

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `input_bridge_v3.c::noncritical_fcns[]` 와 `CallbackBridge.java` 의 native 선언이 일치하는지.
 *
 * RegisterNatives 는 표의 한 항목이라도 이름·시그니처가 어긋나거나 자바 쪽이 native 가
 * 아니면 NoSuchMethodError 를 남기고 실패한다. 그 예외는 JNI_OnLoad 를 그대로 빠져나와
 * libglfw.so 를 System.load 하는 자리에서 터지므로, **모든 버전의 게임이 실행 즉시 튕긴다.**
 * (실제로 그렇게 나간 릴리스가 있었다 — 자바 쪽을 nativeSendData 라우팅 구현으로 바꾼 뒤)
 * 기기에서만 드러나는 고장이라 여기서 못 박아둔다.
 */
class CallbackBridgeNativesTest {

    private val moduleDir = File(".")
    private val bridgeC = File(moduleDir, "src/main/cpp/pojav_jni/input_bridge_v3.c")
    private val bridgeJava = File(moduleDir, "src/main/java/org/lwjgl/glfw/CallbackBridge.java")

    @Test
    fun `네이티브 등록표의 메서드가 모두 자바에 native 로 선언돼 있다`() {
        val table = Regex("""\{"(\w+)",\s*"([^"]+)"""")
            .findAll(bridgeC.readText().substringAfter("noncritical_fcns[] = {").substringBefore("};"))
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("등록표를 못 읽었다", 8, table.size)

        val declared = Regex("""public static native\s+(\S+)\s+(\w+)\s*\(([^)]*)\)""")
            .findAll(bridgeJava.readText())
            .associate { m ->
                val (ret, name, params) = m.destructured
                val args = params.split(",").filter { it.isNotBlank() }
                    .joinToString("") { jni(it.trim().substringBeforeLast(' ').trim()) }
                name to "($args)${jni(ret)}"
            }

        for ((name, sig) in table) {
            assertEquals("CallbackBridge.$name 의 native 선언", sig, declared[name])
        }
    }

    private fun jni(javaType: String): String = when (javaType) {
        "void" -> "V"; "boolean" -> "Z"; "char" -> "C"; "int" -> "I"
        "float" -> "F"; "double" -> "D"; "long" -> "J"
        "String" -> "Ljava/lang/String;"; "byte[]" -> "[B"
        else -> error("모르는 타입: $javaType")
    }
}
