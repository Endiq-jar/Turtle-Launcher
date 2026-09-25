package com.endiq.turtlelauncher.domain.usecase

import com.endiq.turtlelauncher.domain.model.Instance
import com.endiq.turtlelauncher.domain.repository.InstanceRepository
import javax.inject.Inject

/** 설치된 인스턴스 목록을 가져온다. */
class GetInstancesUseCase @Inject constructor(
    private val repository: InstanceRepository
) {
    suspend operator fun invoke(): List<Instance> = repository.getInstances()
}
