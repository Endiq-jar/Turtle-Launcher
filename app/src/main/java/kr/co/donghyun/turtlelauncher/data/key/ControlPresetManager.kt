package kr.co.donghyun.turtlelauncher.data.key

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlin.math.abs

/**
 * The two original Turtle control layouts are kept as real, selectable presets.
 *
 * The old launcher stores positions as expressions because its control view knows the current
 * display dimensions. The renamed launcher uses normalized coordinates instead, so this adapter
 * evaluates the useful screen-relative parts of those expressions and falls back to the same
 * stable grid for expressions that only depend on the old view's runtime variables. The original
 * key codes, labels, sizes, colours, toggle and swipe flags are retained.
 */
object ControlPresetManager {
    const val DEFAULT_PRESET = "default"
    const val SURVIVAL_PRESET = "survival"

    private const val PREFS = "turtle_controls"
    private const val KEY_ACTIVE = "active_preset"
    private const val KEY_LAYOUT_IMPORTED = "layout_imported"
    private const val TAG = "ControlPresetManager"
    private val gson = Gson()

    private data class OldLayout(@SerializedName("mControlDataList") val controls: List<OldControl> = emptyList())

    private data class OldControl(
        val name: String = "",
        val keycodes: List<Int> = emptyList(),
        val dynamicX: String? = null,
        val dynamicY: String? = null,
        val width: Float = 52f,
        val height: Float = 52f,
        val isToggle: Boolean = false,
        val opacity: Float = 0.82f,
        val bgColor: Int = 0x99000000.toInt(),
        val strokeColor: Int = ColorDefaults.WHITE,
        val strokeWidth: Float = 1f,
        val cornerRadius: Float = 10f,
        val isSwipeable: Boolean = false,
        val displayInGame: Boolean = true,
        val displayInMenu: Boolean = false,
        val passThruEnabled: Boolean = false,
    )

    private object ColorDefaults { const val WHITE = -1 }

    private val presets = listOf(DEFAULT_PRESET, SURVIVAL_PRESET)

    /** Copies the bundled layouts to filesDir so they are also available to the editor/exporter. */
    fun ensureInstalled(context: Context) {
        val dir = File(context.filesDir, "controlmap")
        runCatching {
            dir.mkdirs()
            presets.forEach { name ->
                val target = File(dir, "$name.json")
                if (!target.exists()) {
                    val asset = if (name == SURVIVAL_PRESET) {
                        "turtle_control_presets/survival.json"
                    } else "default.json"
                    context.assets.open(asset).use { input -> target.outputStream().use(input::copyTo) }
                }
            }
        }.onFailure { Log.w(TAG, "Could not install bundled control presets", it) }
    }

    fun availablePresets(context: Context): List<String> {
        ensureInstalled(context)
        return presets.filter { File(context.filesDir, "controlmap/$it.json").isFile }
    }

    fun activePreset(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, DEFAULT_PRESET) ?: DEFAULT_PRESET

    /** Imports the original preset and converts it to the current view's persistent model. */
    fun load(context: Context, preset: String = activePreset(context)): List<KeyButton> {
        ensureInstalled(context)
        val safePreset = if (preset in presets) preset else DEFAULT_PRESET
        val file = File(context.filesDir, "controlmap/$safePreset.json")
        return runCatching {
            val layout = gson.fromJson(file.readText(), OldLayout::class.java)
            layout.controls.mapIndexed { index, control ->
                val oldCode = control.keycodes.firstOrNull() ?: 0
                KeyButton(
                    id = "${safePreset}_$index",
                    label = control.name.replace("\\n", "\n"),
                    glfwCode = mapSpecialCode(oldCode),
                    x = expressionCoordinate(control.dynamicX, index, layout.controls.size, horizontal = true),
                    y = expressionCoordinate(control.dynamicY, index, layout.controls.size, horizontal = false),
                    width = control.width.coerceAtLeast(28f),
                    height = control.height.coerceAtLeast(28f),
                    isAccent = oldCode < 0,
                    backgroundColor = control.bgColor,
                    strokeColor = control.strokeColor,
                    opacity = control.opacity.coerceIn(0f, 1f),
                    cornerRadius = control.cornerRadius.coerceAtLeast(0f),
                    isSwipeable = control.isSwipeable,
                    displayInGame = control.displayInGame,
                    displayInMenu = control.displayInMenu,
                    passThruEnabled = control.passThruEnabled,
                    isToggle = control.isToggle,
                )
            }.filter { it.displayInGame || it.displayInMenu }
        }.onFailure { Log.w(TAG, "Could not load $safePreset control preset", it) }
            .getOrDefault(emptyList())
    }

    fun select(context: Context, preset: String): List<KeyButton> {
        val safePreset = if (preset in presets) preset else DEFAULT_PRESET
        val layout = load(context, safePreset)
        if (layout.isNotEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE, safePreset)
                .putBoolean(KEY_LAYOUT_IMPORTED, true)
                .apply()
            KeyLayoutManager.save(context, layout)
        }
        return layout
    }

    fun cycle(context: Context): Pair<String, List<KeyButton>> {
        val available = availablePresets(context)
        val current = activePreset(context)
        val next = available[(available.indexOf(current).takeIf { it >= 0 }?.plus(1) ?: 0) % available.size]
        return next to select(context, next)
    }

    /** Old special-button IDs mapped to the current input bridge's explicit operations. */
    private fun mapSpecialCode(code: Int): Int = when (code) {
        -1 -> -6 // keyboard
        -2 -> -8 // GUI/controller visibility toggle
        -3 -> -1 // primary mouse
        -4 -> -2 // secondary mouse
        else -> code
    }

    private fun expressionCoordinate(expression: String?, index: Int, count: Int, horizontal: Boolean): Float {
        if (expression.isNullOrBlank()) return fallback(index, count, horizontal)
        val compact = expression.replace(" ", "")
        val screenToken = if (horizontal) "${'$'}{screen_width}" else "${'$'}{screen_height}"
        val screenPattern = Regex.escape(screenToken)
        Regex("([0-9]+(?:\\.[0-9]+)?)\\*?$screenPattern").find(compact)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            return it.coerceIn(0.04f, 0.96f)
        }
        if (horizontal && (compact.contains("${'$'}{screen_width}-") || compact.contains("${'$'}{right}"))) return 0.94f
        if (!horizontal && compact.contains("${'$'}{screen_height}-")) return 0.88f
        // The expression contains px()/margin/width terms from the old dp-based evaluator.
        // Keep it on the same side and in deterministic order rather than dropping the control.
        val side = if (horizontal) {
            if (compact.contains("screen_width") || compact.contains("right")) 0.86f else 0.12f
        } else {
            if (compact.contains("screen_height") && compact.contains("-") ) 0.82f else 0.16f
        }
        return (side + ((index % 5) - 2) * 0.055f).coerceIn(0.04f, 0.96f)
    }

    private fun fallback(index: Int, count: Int, horizontal: Boolean): Float {
        val columns = if (horizontal) 8 else 5
        val row = index / columns
        val column = index % columns
        return if (horizontal) {
            (0.07f + column * 0.12f).coerceIn(0.04f, 0.96f)
        } else {
            (0.12f + row * 0.18f).coerceIn(0.04f, 0.96f)
        }
    }
}
