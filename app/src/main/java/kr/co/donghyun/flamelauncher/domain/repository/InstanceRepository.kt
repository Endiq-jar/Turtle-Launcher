package kr.co.donghyun.flamelauncher.domain.repository

import kr.co.donghyun.flamelauncher.domain.model.DownloadProgress
import kr.co.donghyun.flamelauncher.domain.model.Instance
import kr.co.donghyun.flamelauncher.domain.model.LaunchParams
import kr.co.donghyun.flamelauncher.domain.model.McVersion

/**
 * 인스턴스(설치된 실행 단위)에 대한 리포지토리 계약.
 * data.repository.InstanceRepositoryImpl 이 구현하며, 실제로는 기존
 * data.instance.InstanceManager + MinecraftDownloader + FabricInstaller +
 * ForgeInstaller 를 감싼다(로직을 새로 짜지 않고 어댑터로 감싸는 방식 — 회귀버그 위험 최소화).
 */
interface InstanceRepository {

    /** 설치된 인스턴스 전체 목록. 최근 실행/수정 순으로 정렬해서 반환한다. */
    suspend fun getInstances(): List<Instance>

    /** 바닐라 버전 설치. 완료되면 실행에 필요한 정보를 반환한다. */
    suspend fun installVanilla(
        version: McVersion,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams

    /** Fabric 로더 설치. */
    suspend fun installFabric(
        version: McVersion,
        loaderVersion: String,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams

    /** Forge/NeoForge 로더 설치. */
    suspend fun installForge(
        version: McVersion,
        loaderVersion: String,
        isNeoForge: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): LaunchParams

    /** 이미 설치된 인스턴스를 실행 준비(native 라이브러리/LWJGL 복사 등)한다. */
    suspend fun prepareLaunch(instance: Instance): LaunchParams
}
