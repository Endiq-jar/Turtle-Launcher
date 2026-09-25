package kr.co.donghyun.flamelauncher.domain.usecase

import kr.co.donghyun.flamelauncher.domain.model.Instance
import kr.co.donghyun.flamelauncher.domain.repository.InstanceRepository
import javax.inject.Inject

/** 설치된 인스턴스 목록을 가져온다. */
class GetInstancesUseCase @Inject constructor(
    private val repository: InstanceRepository
) {
    suspend operator fun invoke(): List<Instance> = repository.getInstances()
}
