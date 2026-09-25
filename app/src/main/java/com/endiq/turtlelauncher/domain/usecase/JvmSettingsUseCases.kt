package com.endiq.turtlelauncher.domain.usecase

import com.endiq.turtlelauncher.domain.model.JvmSettings
import com.endiq.turtlelauncher.domain.repository.JvmSettingsRepository
import javax.inject.Inject

class GetJvmSettingsUseCase @Inject constructor(
    private val repository: JvmSettingsRepository
) {
    suspend operator fun invoke(): JvmSettings = repository.getSettings()
}

class SaveJvmSettingsUseCase @Inject constructor(
    private val repository: JvmSettingsRepository
) {
    suspend operator fun invoke(settings: JvmSettings) = repository.saveSettings(settings)
}

class ResetJvmSettingsUseCase @Inject constructor(
    private val repository: JvmSettingsRepository
) {
    suspend operator fun invoke(): JvmSettings = repository.resetSettings()
}
