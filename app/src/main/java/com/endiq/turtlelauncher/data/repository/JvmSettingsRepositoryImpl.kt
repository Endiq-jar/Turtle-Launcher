package com.endiq.turtlelauncher.data.repository

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.endiq.turtlelauncher.data.jvm.JvmSettingsManager
import com.endiq.turtlelauncher.data.mapper.toData
import com.endiq.turtlelauncher.data.mapper.toDomain
import com.endiq.turtlelauncher.domain.model.JvmSettings
import com.endiq.turtlelauncher.domain.repository.JvmSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JvmSettingsRepository 구현체 — 기존 JvmSettingsManager 를 감싼다.
 * "기기 RAM 기반 스마트 기본값 보정"(예전엔 SettingsScreen Composable 안에 있었음)도
 * 여기로 옮겼다 — Context(ActivityManager)가 필요한 로직은 domain 이 아니라 data 레이어 몫.
 */
@Singleton
class JvmSettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : JvmSettingsRepository {

    override suspend fun getSettings(): JvmSettings {
        val loaded = JvmSettingsManager.load(context)
        // 레거시 기본값(2048)이 그대로면 기기 실제 RAM 기반 절반 값으로 보정.
        val adjusted = if (loaded.maxHeapMb == 2048) {
            val defaultHeapMb = detectDefaultHeapMb()
            loaded.copy(maxHeapMb = defaultHeapMb, minHeapMb = defaultHeapMb / 4)
        } else loaded
        return adjusted.toDomain()
    }

    override suspend fun saveSettings(settings: JvmSettings) {
        JvmSettingsManager.save(context, settings.toData())
    }

    override suspend fun resetSettings(): JvmSettings =
        JvmSettingsManager.reset(context).toDomain()

    private fun detectDefaultHeapMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / 1024 / 1024).toInt()
        val maxHeapMb = (totalRamMb / 256) * 256
        return maxHeapMb / 2
    }
}
