package com.endiq.turtlelauncher.ui.subassembly.view

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import android.widget.TextView
import com.getkeepsafe.taptargetview.TapTargetView
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.inputstats.InputStatsTracker
import com.endiq.turtlelauncher.feature.inputstats.SessionStatsTracker
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.utils.NewbieGuideUtils
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.formatFileSize
import com.endiq.turtlelauncher.utils.platform.MemoryUtils
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.assist.helper.FxScopeHelper
import com.petterp.floatingx.listener.IFxViewLifecycle
import com.petterp.floatingx.listener.control.IFxScopeControl
import com.petterp.floatingx.view.FxViewHolder
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import org.lwjgl.glfw.CallbackBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * TurtleLauncher: the in-game menu button and the info HUD (FPS, memory,
 * CPS, keystrokes, etc.) used to share one draggable floating bubble.
 * They're now two independent floating windows so the player can
 * position/drag them separately — tapping the gear opens the menu, the
 * info panel is purely informational and never intercepts taps.
 */
class GameMenuViewWrapper(
    private val activity: Activity,
    private val listener: View.OnClickListener,
    private val showInfo: Boolean
) {
    companion object {
        private const val TAG = "GameMenuViewWrapper"
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    private var timer: Timer? = null
    private val memoryText: String = AllSettings.gameMenuMemoryText.getValue()

    private var showMemory: Boolean = false
    private var showNativeMemory: Boolean = false
    private var showFPS: Boolean = false
    private var showCps: Boolean = false
    private var showKeystrokes: Boolean = false
    private var showMousestrokes: Boolean = false
    private var showStopwatch: Boolean = false
    private var showPlaytime: Boolean = false
    private var showSystemResources: Boolean = false
    private var showTime: Boolean = false
    private var showRamGraph: Boolean = false
    private var showPing: Boolean = false
    private var showScreenshotButton: Boolean = false
    private var showRecordButton: Boolean = false
    private var buttonVisible: Boolean = false

    private var buttonScopeFx: IFxScopeControl? = null
    private var hudScopeFx: IFxScopeControl? = null

    init {
        refreshState()
        thinkForButtonVisibility()
        if (showInfo) thinkForHudVisibility()
    }

    private fun getButtonWindow(): IFxScopeControl {
        return FxScopeHelper.Builder().apply {
            setLayout(R.layout.view_game_menu_window)
            setOnClickListener(0L, listener)
            setEnableEdgeAdsorption(false)
            addViewLifecycle(object : IFxViewLifecycle {
                override fun initView(holder: FxViewHolder) {
                    holder.view.alpha = AllSettings.gameMenuAlpha.getValue().toFloat() / 100f
                }

                override fun detached(view: View) {}
            })
            setGravity(getCurrentGravity())
        }.build().toControl(activity)
    }

    private fun getHudWindow(): IFxScopeControl {
        return FxScopeHelper.Builder().apply {
            setLayout(R.layout.view_game_info_hud)
            setEnableEdgeAdsorption(false)
            addViewLifecycle(object : IFxViewLifecycle {
                override fun initView(holder: FxViewHolder) {
                    holder.view.alpha = AllSettings.hudAlpha.getValue().toFloat() / 100f
                    updateInfoText(holder.view)
                }

                override fun detached(view: View) {
                    cancelInfoTimer()
                }
            })
            setGravity(getHudGravity())
        }.build().toControl(activity)
    }

    private fun startNewbieGuide(mainView: View) {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        TapTargetView.showFor(
            activity,
            NewbieGuideUtils.getSimpleTarget(activity, mainView,
                activity.getString(R.string.setting_category_game_menu),
                activity.getString(R.string.newbie_guide_game_menu)
            )
        )
    }

    /** Controls only the menu button's visibility (e.g. hidden when the control layout already has its own menu button). */
    fun setVisibility(visible: Boolean) {
        this.buttonVisible = visible
        thinkForButtonVisibility()
    }

    /** Called when relevant settings (HUD module toggles, alpha, position, etc.) change. */
    fun refreshSettingsState() {
        refreshState()
        thinkForButtonVisibility()
        if (showInfo) thinkForHudVisibility()
    }

    private fun thinkForButtonVisibility() {
        if (buttonVisible) {
            if (buttonScopeFx == null) {
                buttonScopeFx = getButtonWindow().apply {
                    show()
                    getView()?.let { startNewbieGuide(it) }
                }
            }
        } else {
            buttonScopeFx?.cancel()
            buttonScopeFx = null
        }
    }

    private fun thinkForHudVisibility() {
        val shouldShow = showMemory || showFPS || showCps || showKeystrokes || showMousestrokes ||
            showStopwatch || showPlaytime || showSystemResources || showTime ||
            showRamGraph || showPing || showScreenshotButton || showRecordButton

        if (shouldShow) {
            if (hudScopeFx != null) {
                updateInfoText()
            } else {
                hudScopeFx = getHudWindow().apply {
                    updateInfoText()
                    show()
                }
            }
        } else {
            hudScopeFx?.cancel()
            hudScopeFx = null
            cancelInfoTimer()
        }
    }

    private fun refreshState() {
        showMemory = AllSettings.gameMenuShowMemory.getValue()
        showNativeMemory = AllSettings.gameMenuShowNativeMemory.getValue()
        showFPS = AllSettings.gameMenuShowFPS.getValue()
        showCps = AllSettings.showCpsHud.getValue()
        showKeystrokes = AllSettings.showKeystrokesHud.getValue()
        showMousestrokes = AllSettings.showMousestrokesHud.getValue()
        showStopwatch = AllSettings.showStopwatchHud.getValue()
        showPlaytime = AllSettings.showPlaytimeHud.getValue()
        showSystemResources = AllSettings.showSystemResourcesHud.getValue()
        showTime = AllSettings.showTimeHud.getValue()
        showRamGraph = AllSettings.showRamGraphHud.getValue()
        showPing = AllSettings.showPingHud.getValue()
        showScreenshotButton = AllSettings.showScreenshotButtonHud.getValue()
        showRecordButton = AllSettings.showRecordButtonHud.getValue()
    }

    private fun updateInfoText() {
        hudScopeFx?.getView()?.apply {
            updateInfoText(this)
        }
    }

    private fun updateInfoText(view: View) {
        cancelInfoTimer()

        val memoryText: TextView = view.findViewById(R.id.memory_text)
        val fpsText: TextView = view.findViewById(R.id.fps_text)
        val cpsText: TextView = view.findViewById(R.id.cps_text)
        val systemResourcesText: TextView = view.findViewById(R.id.system_resources_text)
        val timeText: TextView = view.findViewById(R.id.time_text)
        val stopwatchText: TextView = view.findViewById(R.id.stopwatch_text)
        val playtimeText: TextView = view.findViewById(R.id.playtime_text)
        val pingText: TextView = view.findViewById(R.id.ping_text)
        val ramGraphView: com.endiq.turtlelauncher.feature.turtle.RamGraphView = view.findViewById(R.id.ram_graph_view)
        val screenshotButton: TextView = view.findViewById(R.id.screenshot_button)
        val recordButton: TextView = view.findViewById(R.id.record_button)
        val recordTimerText: TextView = view.findViewById(R.id.record_timer_text)

        val keystrokesBlock: View = view.findViewById(R.id.keystrokes_block)
        val keyW: TextView = view.findViewById(R.id.key_w)
        val keyA: TextView = view.findViewById(R.id.key_a)
        val keyS: TextView = view.findViewById(R.id.key_s)
        val keyD: TextView = view.findViewById(R.id.key_d)
        val keySpace: TextView = view.findViewById(R.id.key_space)

        val mousestrokesBlock: View = view.findViewById(R.id.mousestrokes_block)
        val mouseLeft: TextView = view.findViewById(R.id.mouse_left)
        val mouseRight: TextView = view.findViewById(R.id.mouse_right)

        val heldColor = android.graphics.Color.parseColor("#9CCF93") // Status: Success, when held
        val idleColor = android.graphics.Color.parseColor("#33F2F5F0") // faint white when idle

        fun setKeyColor(key: TextView, held: Boolean) {
            key.setBackgroundColor(if (held) heldColor else idleColor)
        }

        fun updateInfoText() {
            if (showMemory) {
                val nativeSuffix = if (showNativeMemory) {
                    val nativeMb = MemoryUtils.getNativeHeapAllocated() / (1024 * 1024)
                    " (N: ${nativeMb}M)"
                } else ""
                val memoryString = "${this@GameMenuViewWrapper.memoryText} ${getUsedDeviceMemory()}/${getTotalDeviceMemory()}$nativeSuffix".let { string ->
                    if (string.length > 40) return@let string.take(40)
                    string
                }
                TaskExecutors.runInUIThread { memoryText.text = memoryString }
            }
            if (showFPS) {
                val fpsString = "FPS: ${CallbackBridge.getCurrentFps()}"
                TaskExecutors.runInUIThread { fpsText.text = fpsString }
            }
            if (showCps) {
                val cpsString = "CPS: ${InputStatsTracker.getCps()}"
                TaskExecutors.runInUIThread { cpsText.text = cpsString }
            }
            if (showSystemResources) {
                val batteryPercent = getBatteryPercent()
                val batteryString = if (batteryPercent >= 0) "Battery: $batteryPercent%" else "Battery: --"
                TaskExecutors.runInUIThread { systemResourcesText.text = batteryString }
            }
            if (showTime) {
                val timeString = timeFormat.format(Date())
                TaskExecutors.runInUIThread { timeText.text = timeString }
            }
            if (showStopwatch) {
                val stopwatchString = "Session: ${SessionStatsTracker.formatDuration(SessionStatsTracker.getSessionElapsedMs())}"
                TaskExecutors.runInUIThread { stopwatchText.text = stopwatchString }
            }
            if (showPlaytime) {
                val playtimeString = "Playtime: ${SessionStatsTracker.formatDuration(SessionStatsTracker.getTotalPlaytimeMs())}"
                TaskExecutors.runInUIThread { playtimeText.text = playtimeString }
            }
            if (showKeystrokes) {
                val wHeld = InputStatsTracker.isKeyHeld(LwjglGlfwKeycode.GLFW_KEY_W.toInt())
                val aHeld = InputStatsTracker.isKeyHeld(LwjglGlfwKeycode.GLFW_KEY_A.toInt())
                val sHeld = InputStatsTracker.isKeyHeld(LwjglGlfwKeycode.GLFW_KEY_S.toInt())
                val dHeld = InputStatsTracker.isKeyHeld(LwjglGlfwKeycode.GLFW_KEY_D.toInt())
                val spaceHeld = InputStatsTracker.isKeyHeld(LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt())
                TaskExecutors.runInUIThread {
                    setKeyColor(keyW, wHeld)
                    setKeyColor(keyA, aHeld)
                    setKeyColor(keyS, sHeld)
                    setKeyColor(keyD, dHeld)
                    setKeyColor(keySpace, spaceHeld)
                }
            }
            if (showMousestrokes) {
                val leftHeld = InputStatsTracker.isLeftMouseHeld()
                val rightHeld = InputStatsTracker.isRightMouseHeld()
                TaskExecutors.runInUIThread {
                    setKeyColor(mouseLeft, leftHeld)
                    setKeyColor(mouseRight, rightHeld)
                }
            }
            if (showPing) {
                val ms = com.endiq.turtlelauncher.feature.turtle.PingTracker.getPingMs()
                val pingString = if (ms >= 0) "Ping: $ms ms" else "Ping: -- ms"
                TaskExecutors.runInUIThread { pingText.text = pingString }
            }
            if (showRamGraph) {
                val usedMb = MemoryUtils.getUsedDeviceMemory(activity) / (1024 * 1024)
                val totalMb = MemoryUtils.getTotalDeviceMemory(activity) / (1024 * 1024)
                TaskExecutors.runInUIThread { ramGraphView.pushSample(usedMb, totalMb) }
            }
            // TurtleLauncher: screen recording - live elapsed-time readout + swap the record
            // button's glyph to a stop icon while recording. Runs every info-tick rather than
            // its own timer, same as everything else above.
            if (showRecordButton) {
                val recording = com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.isRecording()
                TaskExecutors.runInUIThread {
                    recordButton.visibility = if (recording) View.VISIBLE else View.GONE
                    recordButton.text = if (recording) "⏹" else "⏺"
                    if (recording && AllSettings.recordingShowTimer.getValue()) {
                        val elapsed = com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.getElapsedSeconds()
                        recordTimerText.text = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
                        recordTimerText.visibility = View.VISIBLE
                    } else {
                        recordTimerText.visibility = View.GONE
                    }
                }
            }
        }

        updateInfoText()

        if (showInfo) {
            memoryText.visibility = if (showMemory) View.VISIBLE else View.GONE
            fpsText.visibility = if (showFPS) View.VISIBLE else View.GONE
            cpsText.visibility = if (showCps) View.VISIBLE else View.GONE
            systemResourcesText.visibility = if (showSystemResources) View.VISIBLE else View.GONE
            timeText.visibility = if (showTime) View.VISIBLE else View.GONE
            stopwatchText.visibility = if (showStopwatch) View.VISIBLE else View.GONE
            playtimeText.visibility = if (showPlaytime) View.VISIBLE else View.GONE
            keystrokesBlock.visibility = if (showKeystrokes) View.VISIBLE else View.GONE
            mousestrokesBlock.visibility = if (showMousestrokes) View.VISIBLE else View.GONE
            pingText.visibility = if (showPing) View.VISIBLE else View.GONE
            ramGraphView.visibility = if (showRamGraph) View.VISIBLE else View.GONE
            screenshotButton.visibility = if (showScreenshotButton) View.VISIBLE else View.GONE
            screenshotButton.setOnClickListener {
                com.endiq.turtlelauncher.feature.turtle.ScreenshotHelper.takeScreenshot(activity)
            }
            if (!showRecordButton) {
                recordButton.visibility = View.GONE
                recordTimerText.visibility = View.GONE
            }
            recordButton.setOnClickListener {
                com.endiq.turtlelauncher.feature.turtle.ScreenRecorder.toggle(activity)
            }

            if (showPing) {
                com.endiq.turtlelauncher.feature.turtle.PingTracker.start()
            }

            val needsTimer = showMemory || showFPS || showCps || showKeystrokes || showMousestrokes ||
                showStopwatch || showPlaytime || showSystemResources || showTime || showPing || showRamGraph ||
                showRecordButton
            if (needsTimer) {
                timer = Timer().apply {
                    schedule(object : TimerTask() {
                        override fun run() {
                            updateInfoText()
                        }
                    }, 0, AllSettings.gameMenuInfoRefreshRate.getValue().toLong())
                }
            }
        }
    }

    private fun getUsedDeviceMemory(): String = formatFileSize(MemoryUtils.getUsedDeviceMemory(activity))

    private fun getTotalDeviceMemory(): String = formatFileSize(MemoryUtils.getTotalDeviceMemory(activity))

    /** Reads the current battery intent synchronously (sticky intent, no receiver actually registered). */
    private fun getBatteryPercent(): Int {
        return try {
            val intent = activity.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun cancelInfoTimer() {
        timer?.cancel()
        timer = null
    }

    private fun getCurrentGravity(): FxGravity {
        return when(AllSettings.gameMenuLocation.getValue()) {
            "left_or_top" -> FxGravity.LEFT_OR_TOP
            "left_or_bottom" -> FxGravity.LEFT_OR_BOTTOM
            "right_or_top" -> FxGravity.RIGHT_OR_TOP
            "right_or_bottom" -> FxGravity.RIGHT_OR_BOTTOM
            "top_or_center" -> FxGravity.TOP_OR_CENTER
            "bottom_or_center" -> FxGravity.BOTTOM_OR_CENTER
            "center" -> FxGravity.CENTER
            else -> FxGravity.CENTER
        }
    }

    /**
     * The info HUD defaults to the opposite top corner from the menu button
     * so the two never spawn stacked on top of each other. Both windows are
     * user-draggable (FloatingX), so this is just a sane starting position,
     * not a fixed one.
     */
    private fun getHudGravity(): FxGravity {
        return when(AllSettings.gameMenuLocation.getValue()) {
            "left_or_top", "left_or_bottom" -> FxGravity.RIGHT_OR_TOP
            else -> FxGravity.LEFT_OR_TOP
        }
    }
}
