package com.endiq.turtlelauncher.domain.model

/** Mojang 버전 매니페스트의 한 항목(도메인 모델). data.mojang.VersionEntry 를 미러링. */
data class McVersion(
    val id: String,
    val type: String,   // "release", "snapshot", "old_beta", "old_alpha"
    val url: String,
    val releaseTime: String,
)
