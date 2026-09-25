package kr.co.donghyun.turtlelauncher.domain.usecase

import kr.co.donghyun.turtlelauncher.domain.model.DownloadProgress
import kr.co.donghyun.turtlelauncher.domain.model.Instance
import kr.co.donghyun.turtlelauncher.domain.model.LaunchParams
import kr.co.donghyun.turtlelauncher.domain.model.McVersion
import kr.co.donghyun.turtlelauncher.domain.repository.InstanceRepository
import javax.inject.Inject

/** 바닐라 버전을 설치한다. */
class InstallVanillaUseCase @Inject constructor(
    private val repository: InstanceRepository
) {
    suspend operator fun invoke(
        version: McVersion,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams = repository.installVanilla(version, onProgress)
}

/** Fabric 로더를 설치한다. */
class InstallFabricUseCase @Inject constructor(
    private val repository: InstanceRepository
) {
    suspend operator fun invoke(
        version: McVersion,
        loaderVersion: String,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams = repository.installFabric(version, loaderVersion, onProgress)
}

/** Forge/NeoForge 로더를 설치한다. */
class InstallForgeUseCase @Inject constructor(
    private val repository: InstanceRepository
) {
    suspend operator fun invoke(
        version: McVersion,
        loaderVersion: String,
        isNeoForge: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams = repository.installForge(version, loaderVersion, isNeoForge, onProgress)
}

/** 이미 설치된 인스턴스를 실행 준비한다(native/lwjgl 복사 등). */
class PrepareLaunchUseCase @Inject constructor(
    private val repository: InstanceRepository
) {
    suspend operator fun invoke(instance: Instance): LaunchParams = repository.prepareLaunch(instance)
}
