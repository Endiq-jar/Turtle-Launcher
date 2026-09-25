package kr.co.donghyun.flamelauncher.presentation.ui.components

import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kr.co.donghyun.flamelauncher.presentation.MinecraftActivity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kr.co.donghyun.flamelauncher.presentation.util.MinecraftActivityBridge
import kr.co.donghyun.flamelauncher.presentation.util.minecraft.MinecraftInputConnection

// 드래그 판정 거리 (px) — 이 이상 움직여야 카메라 회전 시작
private const val DRAG_SLOP = 20f
var downX = 0f
var downY = 0f
var lastX = 0f
var lastY = 0f
var isDragging = false
var isLongPress = false
var isHotbarTouch = false   // 이번 터치가 핫바 영역에서 시작됐나(슬롯 선택 전용)
var longPressRunnable: Runnable? = null
val handler = android.os.Handler(android.os.Looper.getMainLooper())
private const val LONG_PRESS_TIMEOUT = 500L

// 카메라/클릭을 담당하는 "활성 포인터"의 ID. 맨 처음 내려간 손가락만 추적한다.
//   둘째 손가락(블록 캐는 중 인벤/다른 화면 동시 클릭)이 들어와도 카메라 좌표가
//   그쪽으로 점프하지 않도록, 이 ID 의 이벤트만 카메라/클릭에 반영한다.
//   ACTION_POINTER_UP 으로 활성 포인터가 떼질 때 발생하던 "좌표 점프 → 카메라 획 돎"도 차단.
private const val INVALID_POINTER = -1
var activePointerId = INVALID_POINTER

/** 모든 터치 상태를 초기화(grab 전환/surface 재생성/취소 시 stale 값 제거). */
private fun resetTouchState() {
    longPressRunnable?.let { handler.removeCallbacks(it) }
    longPressRunnable = null
    isDragging = false
    isLongPress = false
    isHotbarTouch = false
    activePointerId = INVALID_POINTER
}

@Composable
fun MinecraftSurface(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface, SurfaceHolder) -> Unit,
    onSurfaceChanged: (Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit = {},   // ← 추가
) {
    AndroidView(
        factory = { ctx ->
            val activity = ctx as MinecraftActivity
            // 현재 surface 버퍼 크기(해상도 배율 적용 후). 100% 면 뷰 크기와 같다.
            //   터치(뷰) 좌표를 게임 창 좌표로 변환할 때 buf/view 비율로 사용한다.
            var surfaceBufW = 0
            var surfaceBufH = 0
            object : SurfaceView(ctx) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    val config = resources.configuration
                    val hasHardwareKeyboard = config.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS

                    if (hasHardwareKeyboard) {
                        // 물리 키보드 있음 → onKeyDown/onKeyUp 경로로만 받기
                        return null
                    }

                    outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_ACTION_NONE
                    return MinecraftInputConnection(this, activity)
                }
            }.apply {
                tag = "minecraft_surface"
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) =
                        onSurfaceCreated(holder.surface, holder)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                        surfaceBufW = w
                        surfaceBufH = h
                        onSurfaceChanged(w, h)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })


                setOnKeyListener { _, keyCode, event ->
                    val glfwKey = ctx.androidKeyToGlfw(keyCode) ?: return@setOnKeyListener false
                    val action = if (event.action == android.view.KeyEvent.ACTION_DOWN) 1 else 0
                    ctx.sendKey(glfwKey, action)
                    true
                }

                setOnTouchListener(minecraftTouchListener(activity) { intArrayOf(surfaceBufW, surfaceBufH) })
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * 게임 화면 터치 처리 — 탭·길게 누르기·드래그(시점)·핫바.
 *
 * 26.3+(SDL) 에서는 게임 화면이 SDL 이 만든 SurfaceView 라서, 같은 처리를 그쪽에도
 * 붙인다(MinecraftActivity). 그래서 뷰에 묶지 않고 여기로 뺐다.
 *
 * @param bufferSize 현재 표면 버퍼 크기(해상도 배율 적용 후). 터치(뷰) 좌표를 게임 창
 *                   좌표로 줄일 때 쓴다. 모르면 0 을 주면 된다 — 배율 1 로 본다.
 */
internal fun minecraftTouchListener(
    activity: MinecraftActivity,
    bufferSize: () -> IntArray,
): View.OnTouchListener = View.OnTouchListener { v, event ->
    val buf = bufferSize()

    // ⚠️ Log.i 다 — 테스트 태블릿은 log.tag=I 라 Log.d 가 통째로 안 보인다. 이동마다 찍으면 넘치므로 누를 때만.
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        Log.i("FLAME_LAUNCHER", "Surface 터치: isGrabbing=${activity.isGrabbing}, combat=${activity.combatMode}")
    }
    // ── 해상도 배율 보정 ──
    // surface 버퍼가 화면보다 작으면(해상도<100%) 터치(뷰) 좌표를 게임 창 좌표로 줄여야
    // UI(메뉴/인벤토리) 클릭 위치가 맞는다. 100% 면 buf==뷰라 1.0 → 기존과 동일.
    // (인게임 카메라는 델타 기반이라 변환하지 않는다 → 감도/조준 그대로)
    val coordScaleX = if (v.width > 0 && buf[0] > 0) buf[0].toFloat() / v.width else 1f
    val coordScaleY = if (v.height > 0 && buf[1] > 0) buf[1].toFloat() / v.height else 1f
    try {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 첫 손가락 — 이 포인터를 활성 포인터로 지정
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isDragging = false
                isLongPress = false
                isHotbarTouch = false

                // ── 인게임(grab) + 핫바 영역 터치 → 슬롯 선택 전용 ──
                //   ZL2 방식: 핫바 사각형을 9등분해 x 로 슬롯 결정, 카메라/클릭으로 넘기지 않음.
                if (activity.isGrabbing) {
                    val rect = activity.computeHotbarRect(v.width, v.height)
                    if (rect != null && rect.contains(event.x, event.y)) {
                        val idx = (((event.x - rect.left) / (rect.width() / 9f)).toInt())
                            .coerceIn(0, 8)
                        activity.selectHotbarSlot(idx)
                        isHotbarTouch = true
                        return@OnTouchListener true   // 여기서 종료(카메라/클릭 안 함)
                    }
                }

                if (!activity.isGrabbing) {
                    // ── UI 모드 (인벤토리/메뉴) — 기존 동작 유지 ──
                    activity.currentCursorX = event.x * coordScaleX
                    activity.currentCursorY = event.y * coordScaleY
                    activity.sendCursorPos(activity.currentCursorX, activity.currentCursorY)
                    activity.sendMouseButton(0, 1)
                } else {
                    // ── 인게임 모드 — 롱프레스 타이머 ──
                    // 전투 모드: 길게 = 우클릭 유지 (방패/활)
                    // 일반 모드: 길게 = 좌클릭 유지 (블록 파괴)
                    val longBtn = if (activity.combatMode) 1 else 0
                    longPressRunnable = Runnable {
                        if (!isDragging) {
                            isLongPress = true
                            activity.sendMouseButton(longBtn, 1)  // PRESS
                        }
                    }.also { handler.postDelayed(it, LONG_PRESS_TIMEOUT) }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 둘째 이후 손가락이 내려옴 (블록 캐는 중 인벤/다른 화면 동시 클릭 등).
                // 카메라는 활성 포인터(첫 손가락)만 따라가므로 여기선 아무것도 하지 않는다.
                // → 활성 포인터 좌표/기준점을 건드리지 않아 카메라가 튀지 않음.
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 손가락 하나가 떨어짐. 그게 "활성 포인터"라면 좌표 점프를 막기 위해
                // 남아있는 다른 손가락으로 활성 포인터를 넘기되, 기준점을 그 위치로 리셋한다.
                // (활성 포인터가 떼질 때 발생하던 카메라 획 돎의 직접 원인 제거)
                val upIndex = event.actionIndex
                val upId = event.getPointerId(upIndex)
                if (upId == activePointerId) {
                    // 활성 포인터가 아닌 다른 포인터 하나를 새 활성 포인터로
                    val newIndex = if (upIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        // 기준점을 새 손가락 위치로 리셋 → 델타가 튀지 않음
                        lastX = event.getX(newIndex)
                        lastY = event.getY(newIndex)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isHotbarTouch) return@OnTouchListener true

                // 활성 포인터의 좌표만 사용 (둘째 손가락 움직임은 카메라에 반영 안 함)
                val pIndex = event.findPointerIndex(activePointerId)
                if (pIndex < 0) return@OnTouchListener true
                val px = event.getX(pIndex)
                val py = event.getY(pIndex)

                val totalDx = px - downX
                val totalDy = py - downY

                if (!isDragging &&
                    (totalDx * totalDx + totalDy * totalDy) > DRAG_SLOP * DRAG_SLOP
                ) {
                    isDragging = true
                    if (!isLongPress) {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                    // 드래그 시작 순간 기준점을 현재 위치로 리셋 →
                    //   슬롭(20px) 넘는 동안의 이동이 카메라에 한꺼번에 튀지 않게 함.
                    lastX = px
                    lastY = py
                }

                if (isDragging) {
                    if (activity.isGrabbing) {
                        // 인게임 — 델타 기반 카메라 회전.
                        // ⚠️ moveLookBy 로 보낸다. 26.3(SDL)은 마우스를 잡은 상태에서
                        //    **상대 이동량(xrel/yrel)만** 읽어서, 절대 좌표로 보내면 시점이
                        //    꿈쩍도 안 한다. moveLookBy 가 두 경로를 다 맞게 처리한다.
                        activity.moveLookBy(px - lastX, py - lastY)
                    } else {
                        // UI — 절대 좌표
                        activity.currentCursorX = px * coordScaleX
                        activity.currentCursorY = py * coordScaleY
                        activity.sendCursorPos(activity.currentCursorX, activity.currentCursorY)
                    }
                }

                lastX = px
                lastY = py
            }

            MotionEvent.ACTION_UP -> {
                if (isHotbarTouch) {
                    resetTouchState()
                    return@OnTouchListener true
                }
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null

                if (activity.isGrabbing) {
                    // ── 인게임 모드 ──
                    if (isLongPress) {
                        // 롱프레스 중이었으면 해당 버튼 release
                        val longBtn = if (activity.combatMode) 1 else 0
                        activity.sendMouseButton(longBtn, 0)  // RELEASE
                    } else if (!isDragging) {
                        // 짧은 탭
                        // 전투 모드: 탭 = 좌클릭 (공격)
                        // 일반 모드: 탭 = 우클릭 (놓기/상호작용)
                        val tapBtn = if (activity.combatMode) 0 else 1
                        activity.sendMouseButton(tapBtn, 1)   // PRESS
                        handler.postDelayed({
                            activity.sendMouseButton(tapBtn, 0)   // RELEASE
                        }, 50)
                    }
                } else {
                    // ── UI 모드 — 좌클릭 release ──
                    activity.sendMouseButton(0, 0)
                }

                resetTouchState()
            }

            MotionEvent.ACTION_CANCEL -> {
                // 안전망: 어떤 이유로 취소되면 모든 버튼 release + 상태 초기화
                if (isLongPress && activity.isGrabbing) {
                    val longBtn = if (activity.combatMode) 1 else 0
                    activity.sendMouseButton(longBtn, 0)
                }
                resetTouchState()
            }
        }
    } catch (_: Exception) {}
    true
}
