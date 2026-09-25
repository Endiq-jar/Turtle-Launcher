package com.endiq.turtlelauncher.domain.repository

import com.endiq.turtlelauncher.data.auth.ThirdPartyLoginRequest
import com.endiq.turtlelauncher.domain.model.UserSession

/** 로그인 세션 조회 + 로그인 플로우 계약. SAF/웹뷰 자체는 여전히 LoginActivity 몫. */
interface AuthRepository {
    /** 현재 저장된 세션(없거나 refreshToken 이 비어있으면 null). */
    fun getSession(): UserSession?

    /** MS 로그인 웹뷰에 로드할 인증 URL. */
    fun getAuthUrl(): String

    /** 이 URL이 로그인 완료 리다이렉트 URI 인지(웹뷰에서 가로챌 시점 판단용). */
    fun isRedirectUri(url: String): Boolean

    /** 인증 코드로 실제 로그인을 완료하고 세션을 저장 후 반환한다. */
    suspend fun login(code: String): UserSession

    /** Creates/updates an explicitly offline account without contacting a server. */
    fun loginOffline(username: String): UserSession

    /** Logs in through a standard authlib-injector/Yggdrasil account server. */
    suspend fun loginThirdParty(request: ThirdPartyLoginRequest): UserSession
}
