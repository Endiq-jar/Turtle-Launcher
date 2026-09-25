package com.endiq.turtlelauncher.domain.model

/**
 * 설치/준비가 끝나서 실제로 MinecraftActivity 를 띄울 때 필요한 정보를 담는 결과 모델.
 * install/prepareLaunch UseCase 들의 반환 타입.
 */
data class LaunchParams(
    val versionId: String,
    val assetIndexId: String,
    val mainClass: String,
    val extraJars: List<String> = emptyList(),
    val instanceDirPath: String,
)

/** 로그인된 사용자 세션(도메인 모델). data.auth.AuthSession 을 미러링. */
data class UserSession(
    val username: String,
    val uuid: String,
)
