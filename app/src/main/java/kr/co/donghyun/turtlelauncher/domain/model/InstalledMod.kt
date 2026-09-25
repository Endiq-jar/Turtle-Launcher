package kr.co.donghyun.turtlelauncher.domain.model

data class InstalledMod(
    val fileName: String,
    val displayName: String,
    val enabled: Boolean,
    val sizeBytes: Long,
)
