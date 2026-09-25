package kr.co.donghyun.turtlelauncher.data.mapper

import kr.co.donghyun.turtlelauncher.data.jvm.JvmSettings as DataJvmSettings
import kr.co.donghyun.turtlelauncher.domain.model.JvmSettings as DomainJvmSettings

fun DataJvmSettings.toDomain(): DomainJvmSettings = DomainJvmSettings(
    maxHeapMb = maxHeapMb,
    minHeapMb = minHeapMb,
    useG1GC = useG1GC,
    gcPauseMillis = gcPauseMillis,
    parallelRefProc = parallelRefProc,
    heapRegionSizeMb = heapRegionSizeMb,
    disableClouds = disableClouds,
    extraJvmArgs = extraJvmArgs,
    mouseSensitivity = mouseSensitivity,
    renderDistance = renderDistance,
    graphicsMode = graphicsMode,
    cacheDirPath = cacheDirPath,
    unlockFps = unlockFps,
    fullscreen = fullscreen,
    resolutionScalePercent = resolutionScalePercent,
)

fun DomainJvmSettings.toData(): DataJvmSettings = DataJvmSettings(
    maxHeapMb = maxHeapMb,
    minHeapMb = minHeapMb,
    useG1GC = useG1GC,
    gcPauseMillis = gcPauseMillis,
    parallelRefProc = parallelRefProc,
    heapRegionSizeMb = heapRegionSizeMb,
    disableClouds = disableClouds,
    extraJvmArgs = extraJvmArgs,
    mouseSensitivity = mouseSensitivity,
    renderDistance = renderDistance,
    graphicsMode = graphicsMode,
    cacheDirPath = cacheDirPath,
    unlockFps = unlockFps,
    fullscreen = fullscreen,
    resolutionScalePercent = resolutionScalePercent,
)
