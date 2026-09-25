package com.endiq.turtlelauncher.data.repository

import com.endiq.turtlelauncher.data.mapper.toDomain
import com.endiq.turtlelauncher.domain.model.McVersion
import com.endiq.turtlelauncher.domain.model.MinecraftSupport
import com.endiq.turtlelauncher.domain.repository.McVersionRepository
import com.endiq.turtlelauncher.presentation.util.minecraft.VersionRepository as MojangVersionRepository
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
