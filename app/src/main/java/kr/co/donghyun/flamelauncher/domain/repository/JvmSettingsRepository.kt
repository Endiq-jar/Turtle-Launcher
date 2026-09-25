package kr.co.donghyun.flamelauncher.domain.repository

import kr.co.donghyun.flamelauncher.domain.model.JvmSettings

/** JVM/게임플레이 설정 저장소 계약. */
interface JvmSettingsRepository {
    /** 저장된 설정을 가져온다. 기기 RAM 기반 스마트 기본값 보정은 구현체(data 레이어) 책임. */
    suspend fun getSettings(): JvmSettings
    suspend fun saveSettings(settings: JvmSettings)
    suspend fun resetSettings(): JvmSettings
}
