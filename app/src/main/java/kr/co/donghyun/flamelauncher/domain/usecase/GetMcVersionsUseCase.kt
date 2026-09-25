package kr.co.donghyun.flamelauncher.domain.usecase

import kr.co.donghyun.flamelauncher.domain.model.McVersion
import kr.co.donghyun.flamelauncher.domain.repository.McVersionRepository
import javax.inject.Inject

/** Mojang 버전 목록을 가져온다. */
class GetMcVersionsUseCase @Inject constructor(
    private val repository: McVersionRepository
) {
    suspend operator fun invoke(): List<McVersion> = repository.getVersions()
}
