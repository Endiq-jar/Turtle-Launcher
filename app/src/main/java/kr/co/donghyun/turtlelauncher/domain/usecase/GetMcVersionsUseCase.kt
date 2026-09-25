package kr.co.donghyun.turtlelauncher.domain.usecase

import kr.co.donghyun.turtlelauncher.domain.model.McVersion
import kr.co.donghyun.turtlelauncher.domain.repository.McVersionRepository
import javax.inject.Inject

/** Mojang 버전 목록을 가져온다. */
class GetMcVersionsUseCase @Inject constructor(
    private val repository: McVersionRepository
) {
    suspend operator fun invoke(): List<McVersion> = repository.getVersions()
}
