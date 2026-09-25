package com.endiq.turtlelauncher.domain.model

/** 설치/다운로드 진행 상태(도메인 모델). data.mojang.DownloadProgress 를 미러링. */
data class DownloadProgress(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val current: Int = 0,
    val total: Int = 0,
    val fileName: String = "",
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) current.toFloat() / total else 0f
    val percent: Int get() = (fraction * 100).toInt()
}

enum class DownloadPhase {
    IDLE,
    FETCHING_MANIFEST,
    DOWNLOADING_CLIENT,
    DOWNLOADING_LIBRARIES,
    DOWNLOADING_ASSETS,
    DONE,
    ERROR,
}
