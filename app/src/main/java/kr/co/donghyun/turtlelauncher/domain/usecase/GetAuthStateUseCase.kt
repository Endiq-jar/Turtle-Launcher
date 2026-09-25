package kr.co.donghyun.turtlelauncher.domain.usecase

import kr.co.donghyun.turtlelauncher.domain.model.UserSession
import kr.co.donghyun.turtlelauncher.domain.repository.AuthRepository
import javax.inject.Inject

/** 현재 로그인 세션을 가져온다(없으면 null). */
class GetAuthStateUseCase @Inject constructor(
    private val repository: AuthRepository
) {
    operator fun invoke(): UserSession? = repository.getSession()
}
