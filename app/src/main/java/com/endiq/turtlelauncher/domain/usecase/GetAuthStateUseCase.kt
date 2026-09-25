package com.endiq.turtlelauncher.domain.usecase

import com.endiq.turtlelauncher.domain.model.UserSession
import com.endiq.turtlelauncher.domain.repository.AuthRepository
import javax.inject.Inject

/** 현재 로그인 세션을 가져온다(없으면 null). */
class GetAuthStateUseCase @Inject constructor(
    private val repository: AuthRepository
) {
    operator fun invoke(): UserSession? = repository.getSession()
}
