package kr.co.donghyun.turtlelauncher.data.mapper

import kr.co.donghyun.turtlelauncher.data.mojang.DownloadPhase as DataDownloadPhase
import kr.co.donghyun.turtlelauncher.data.mojang.DownloadProgress as DataDownloadProgress
import kr.co.donghyun.turtlelauncher.data.mojang.VersionEntry
import kr.co.donghyun.turtlelauncher.domain.model.DownloadPhase as DomainDownloadPhase
import kr.co.donghyun.turtlelauncher.domain.model.DownloadProgress as DomainDownloadProgress
import kr.co.donghyun.turtlelauncher.domain.model.McVersion

fun VersionEntry.toDomain(): McVersion = McVersion(
    id = id,
    type = type,
    url = url,
    releaseTime = releaseTime,
)

fun McVersion.toData(): VersionEntry = VersionEntry(
    id = id,
    type = type,
    url = url,
    releaseTime = releaseTime,
)

fun DataDownloadProgress.toDomain(): DomainDownloadProgress = DomainDownloadProgress(
    phase = phase.toDomain(),
    current = current,
    total = total,
    fileName = fileName,
    error = error,
)

private fun DataDownloadPhase.toDomain(): DomainDownloadPhase = when (this) {
    DataDownloadPhase.IDLE -> DomainDownloadPhase.IDLE
    DataDownloadPhase.FETCHING_MANIFEST -> DomainDownloadPhase.FETCHING_MANIFEST
    DataDownloadPhase.DOWNLOADING_CLIENT -> DomainDownloadPhase.DOWNLOADING_CLIENT
    DataDownloadPhase.DOWNLOADING_LIBRARIES -> DomainDownloadPhase.DOWNLOADING_LIBRARIES
    DataDownloadPhase.DOWNLOADING_ASSETS -> DomainDownloadPhase.DOWNLOADING_ASSETS
    DataDownloadPhase.DONE -> DomainDownloadPhase.DONE
    DataDownloadPhase.ERROR -> DomainDownloadPhase.ERROR
}

fun DomainDownloadProgress.toData(): DataDownloadProgress = DataDownloadProgress(
    phase = phase.toData(),
    current = current,
    total = total,
    fileName = fileName,
    error = error,
)

private fun DomainDownloadPhase.toData(): DataDownloadPhase = when (this) {
    DomainDownloadPhase.IDLE -> DataDownloadPhase.IDLE
    DomainDownloadPhase.FETCHING_MANIFEST -> DataDownloadPhase.FETCHING_MANIFEST
    DomainDownloadPhase.DOWNLOADING_CLIENT -> DataDownloadPhase.DOWNLOADING_CLIENT
    DomainDownloadPhase.DOWNLOADING_LIBRARIES -> DataDownloadPhase.DOWNLOADING_LIBRARIES
    DomainDownloadPhase.DOWNLOADING_ASSETS -> DataDownloadPhase.DOWNLOADING_ASSETS
    DomainDownloadPhase.DONE -> DataDownloadPhase.DONE
    DomainDownloadPhase.ERROR -> DataDownloadPhase.ERROR
}
