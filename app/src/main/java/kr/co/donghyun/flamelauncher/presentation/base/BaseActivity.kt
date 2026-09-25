package kr.co.donghyun.flamelauncher.presentation.base

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

abstract class BaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // [수정] super.onCreate 이전에 화면 방향을 미리 가로로 고정합니다.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        super.onCreate(savedInstanceState)

        // 창 플래그 및 시스템 바 제어
        applyWindowFlags()

        // 하위 액티비티의 Compose 레이아웃(setContent) 실행
        onCreated()
    }

    var hideNavigation = false
    abstract fun onCreated()

    fun hideNavigation() {
        hideNavigation = true
        applyWindowFlags()
    }

    private fun applyWindowFlags() {
        // 전체 화면 및 화면 켜짐 유지 설정
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 내비게이션 바 제어
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if(hideNavigation) {
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

}
