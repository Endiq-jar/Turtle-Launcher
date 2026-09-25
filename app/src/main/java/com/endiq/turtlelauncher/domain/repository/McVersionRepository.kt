package com.endiq.turtlelauncher.domain.repository

import com.endiq.turtlelauncher.domain.model.McVersion

/**
 * Mojang 버전 매니페스트 리포지토리 계약.
 * 이름을 VersionRepository 가 아니라 McVersionRepository 로 한 이유:
 * presentation.util.minecraft.VersionRepository 라는 기존 구체 클래스가 이미 있어서
 * (패키지가 달라 컴파일은 되지만) 혼동을 피하려고 명시적으로 구분했다.
 */
interface McVersionRepository {
    suspend fun getVersions(): List<McVersion>
}
