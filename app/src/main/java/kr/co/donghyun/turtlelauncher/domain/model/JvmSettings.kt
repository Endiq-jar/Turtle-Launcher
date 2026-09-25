package kr.co.donghyun.turtlelauncher.domain.model

/**
 * JVM/게임플레이 설정 값(도메인 모델). data.jvm.JvmSettings 의 "저장되는 값" 부분만
 * 미러링한다 — toJvmArgArray() 같은 실행시점 로직(Context/File 필요)은 여기 없다.
 * 그 계산은 실제 게임 실행 시점(Phase 5, MinecraftActivity 마이그레이션)에서 다룰 데이터 레이어 몫이다.
 */
data class JvmSettings(
    val maxHeapMb: Int = 4096,
    val minHeapMb: Int = 512,
    val useG1GC: Boolean = true,
    val gcPauseMillis: Int = 100,
    val parallelRefProc: Boolean = true,
    val heapRegionSizeMb: Int = 32,
    val disableClouds: Boolean = true,
    val extraJvmArgs: String = "",
    val mouseSensitivity: Float = 1.5f,
    val renderDistance: Int = 4,
    val graphicsMode: Int = 0,
    val cacheDirPath: String = "",
    val unlockFps: Boolean = true,
    val fullscreen: Boolean = true,
    val resolutionScalePercent: Int = 100,
)
