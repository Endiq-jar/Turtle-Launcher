package kr.co.donghyun.flamelauncher.data.key

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class KeyButton(
    val id: String,
    val label: String,
    val glfwCode: Int,
    val x: Float,
    val y: Float,
    val size: Float = 52f,       // 하위 호환용 (width/height 없을 때)
    val width: Float = size,
    val height: Float = size,
    val isAccent: Boolean = false
)

object KeyLayoutManager {
    private const val FILE_NAME = "key_layout.json"
    private val gson = Gson()

    val DEFAULT_LAYOUT = listOf(
        KeyButton("w",      "W",  87,   0.08f, 0.72f, width = 52f, height = 52f),
        KeyButton("a",      "A",  65,   0.04f, 0.83f, width = 52f, height = 52f),
        KeyButton("s",      "S",  83,   0.08f, 0.83f, width = 52f, height = 52f),
        KeyButton("d",      "D",  68,   0.12f, 0.83f, width = 52f, height = 52f),
        KeyButton("jump",   "🔼",  32,   0.88f, 0.83f, width = 52f, height = 52f),
        KeyButton("sneak",  "🔽",  340,  0.80f, 0.83f, width = 52f, height = 52f),
        KeyButton("sprint", "⏫",  341,  0.84f, 0.83f, width = 52f, height = 52f),
        KeyButton("inv",    "E",  69,   0.92f, 0.72f, width = 52f, height = 52f),
        KeyButton("esc",    "ESC", 256, 0.04f, 0.04f, width = 52f, height = 52f),
        KeyButton("f3",     "F3",  292, 0.08f, 0.04f, width = 52f, height = 52f),
        KeyButton("f5",     "F5",  294, 0.12f, 0.04f, width = 52f, height = 52f),
        KeyButton("t",      "T",   84,  0.04f, 0.14f, width = 52f, height = 52f),
        KeyButton("slash",  "/",   47,  0.08f, 0.14f, width = 52f, height = 52f),
        KeyButton("drop",   "Q",   81,  0.12f, 0.14f, width = 52f, height = 52f),
        KeyButton("keyboard", "⌨️", -6, 0.16f, 0.04f, width = 52f, height = 52f),
        KeyButton("combat", "⚔️", -7, 0.6f, 0.72f, width = 52f, height = 52f, isAccent = true),
    )

    fun load(context: Context): List<KeyButton> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return DEFAULT_LAYOUT
            val type = object : TypeToken<List<KeyButton>>() {}.type
            gson.fromJson<List<KeyButton>>(file.readText(), type)?.map { btn ->
                // 하위 호환: width/height 없으면 size로 채움
                if (btn.width == btn.size && btn.height == btn.size)
                    btn.copy(width = btn.size, height = btn.size)
                else btn
            } ?: DEFAULT_LAYOUT
        } catch (_: Exception) {
            DEFAULT_LAYOUT
        }
    }

    fun save(context: Context, layout: List<KeyButton>) {
        try {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(layout))
        } catch (_: Exception) {}
    }

    fun reset(context: Context): List<KeyButton> {
        save(context, DEFAULT_LAYOUT)
        return DEFAULT_LAYOUT
    }
}