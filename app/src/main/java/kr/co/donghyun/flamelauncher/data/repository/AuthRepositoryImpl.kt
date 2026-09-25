package kr.co.donghyun.flamelauncher.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.co.donghyun.flamelauncher.data.auth.LocalAccountManager
import kr.co.donghyun.flamelauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.flamelauncher.domain.model.UserSession
import kr.co.donghyun.flamelauncher.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/** AuthRepository 구현체 — 기존 MicrosoftAuthManager(SharedPreferences 기반)를 감싼다. */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthRepository {
    override fun getSession(): UserSession? {
        val session = MicrosoftAuthManager.loadSession(context)
        if (session != null && session.refreshToken.isNotEmpty()) {
            return UserSession(username = session.username, uuid = session.uuid)
        }
        return LocalAccountManager.load(context)?.let {
            UserSession(username = it.username, uuid = it.uuid)
        }
    }

    override fun getAuthUrl(): String = MicrosoftAuthManager.getAuthUrl()

    override fun isRedirectUri(url: String): Boolean = url.startsWith(MicrosoftAuthManager.REDIRECT_URI)

    override suspend fun login(code: String): UserSession {
        val session = MicrosoftAuthManager.loginWithCode(code)
        MicrosoftAuthManager.saveSession(context, session)
        LocalAccountManager.clear(context)
        return UserSession(username = session.username, uuid = session.uuid)
    }

    override fun loginOffline(username: String): UserSession {
        MicrosoftAuthManager.clearSession(context)
        val account = LocalAccountManager.save(context, username)
        return UserSession(username = account.username, uuid = account.uuid)
    }
}
