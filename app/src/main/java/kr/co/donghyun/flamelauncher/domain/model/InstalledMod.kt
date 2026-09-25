package kr.co.donghyun.flamelauncher.domain.model

data class InstalledMod(
    val fileName: String,
    val displayName: String,
    val enabled: Boolean,
    val sizeBytes: Long,
)
