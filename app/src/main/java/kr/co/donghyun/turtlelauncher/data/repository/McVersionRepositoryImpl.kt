package kr.co.donghyun.turtlelauncher.data.repository

import kr.co.donghyun.turtlelauncher.data.mapper.toDomain
import kr.co.donghyun.turtlelauncher.domain.model.McVersion
import kr.co.donghyun.turtlelauncher.domain.model.MinecraftSupport
import kr.co.donghyun.turtlelauncher.domain.repository.McVersionRepository
import kr.co.donghyun.turtlelauncher.presentation.util.minecraft.VersionRepository as MojangVersionRepository
import javax.inject.Inject
import javax.inject.Singleton

/** McVersionRepository 구현체 — 기존 presentation.util.minecraft.VersionRepository 를 감싼다. */
@Singleton
class McVersionRepositoryImpl @Inject constructor(
    private val mojangVersionRepository: MojangVersionRepository,
) : McVersionRepository {
    override suspend fun getVersions(): List<McVersion> =
        mojangVersionRepository.fetchVersionList()
            .filter { MinecraftSupport.isSupported(it.id) }
            .map { it.toDomain() }
}
