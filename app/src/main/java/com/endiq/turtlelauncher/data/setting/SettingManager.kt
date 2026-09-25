package com.endiq.turtlelauncher.data.setting

import android.content.Context
import com.google.gson.Gson
import com.endiq.turtlelauncher.data.jvm.JvmSettings
import java.io.File

data class Setting(
    val neverShowCautionAgain : Boolean = false,
    // 사용자가 "이 버전 건너뛰기"를 누른 업데이트 태그(예: "v2.0.0"). 이 버전은 다시 안내하지 않음.
    val skippedUpdateVersion : String? = null,
    // Turtle의 전역 애니메이션 설정. 누락된 구버전 JSON은 Gson 기본값을 사용한다.
    val animationsEnabled: Boolean = true,
    val animationStyle: String = "fade_in",
    // 선택된 런처 배경/스킨 URI는 다음 UI 단계가 소비할 수 있도록 저장한다.
    val backgroundUri: String? = null,
    val skinUri: String? = null,
)

object SettingManager {
    private const val FILE_NAME = "setting.json"
    private val gson = Gson()

    fun load(context: Context): Setting {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return Setting()
            val settings = gson.fromJson(file.readText(), Setting::class.java)
                ?: Setting()

            settings
        } catch (_: Exception) {
            Setting()
        }
    }

    fun save(context: Context, settings: Setting) {
        try {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(settings))
        } catch (_: Exception) {}
    }
}