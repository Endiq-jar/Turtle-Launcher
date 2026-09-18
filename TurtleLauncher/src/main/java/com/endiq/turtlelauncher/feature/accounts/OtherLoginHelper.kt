package com.endiq.turtlelauncher.feature.accounts

import android.content.Context
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.login.AuthResult
import com.endiq.turtlelauncher.feature.login.OtherLoginApi
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.SelectRoleDialog
import net.endiq.launcher.value.MinecraftAccount
import java.util.Objects

/**
 * Helps logging into external accounts (creating a new one or login-only flows).
 */
class OtherLoginHelper(
    private val baseUrl: String,
    private val serverName: String,
    private val email: String,
    private val password: String,
    private val listener: OnLoginListener
) {
    private fun login(context: Context, loginListener: LoginAccountListener) {
        Task.runTask {
            OtherLoginApi.setBaseUrl(baseUrl)
            OtherLoginApi.login(context, email, password,
                object : OtherLoginApi.Listener {
                    override fun onSuccess(authResult: AuthResult) {
                        if (!Objects.isNull(authResult.selectedProfile)) {
                            loginListener.onlyOneRole(authResult)
                        } else {
                            loginListener.hasMultipleRoles(authResult)
                        }
                    }

                    override fun onFailed(error: String) {
                        TaskExecutors.runInUIThread {
                            listener.unLoading()
                            listener.onFailed(error)
                        }
                    }
                })
        }.beforeStart(TaskExecutors.getAndroidUI()) {
            listener.onLoading()
        }.onThrowable { e ->
            val message = "An exception was encountered while performing the login task."
            Logging.e("Other Login", message, e)
            TaskExecutors.runInUIThread {
                listener.onFailed(e.message ?: message)
            }
        }.execute()
    }

    /**
     * Write the account info into the account object (kept separate so login-only flows can
     * refresh the info).
     * @param account the account to write
     */
    private fun writeAccount(
        account: MinecraftAccount,
        authResult: AuthResult,
        userName: String,
        profileId: String,
        updateSkin: Boolean = true,
    ) {
        account.apply {
            this.accessToken = authResult.accessToken
            this.clientToken = authResult.clientToken
            this.otherBaseUrl = baseUrl
            this.otherAccount = email
            this.otherPassword = password
            this.accountType = serverName
            this.username = userName
            this.profileId = profileId
        }
        if (updateSkin) account.updateOtherSkin()
    }

    /**
     * Log into a new account with username and password.
     */
    fun createNewAccount(context: Context) {
        login(context, object : LoginAccountListener {
            override fun onlyOneRole(authResult: AuthResult) {
                val profileId = authResult.selectedProfile.id
                val account: MinecraftAccount = MinecraftAccount.loadFromProfileID(profileId) ?: MinecraftAccount()
                writeAccount(account, authResult, authResult.selectedProfile.name, profileId)
                TaskExecutors.runInUIThread {
                    listener.unLoading()
                    listener.onSuccess(account)
                }
            }

            override fun hasMultipleRoles(authResult: AuthResult) {
                TaskExecutors.runInUIThread {
                    val selectRoleDialog = SelectRoleDialog(
                        context,
                        authResult.availableProfiles
                    ) { selectedProfile ->
                        val profileId = selectedProfile.id
                        val account: MinecraftAccount = MinecraftAccount.loadFromProfileID(profileId) ?: MinecraftAccount()
                        writeAccount(account, authResult, selectedProfile.name, profileId, updateSkin = false)
                        refresh(context, account)
                    }
                    listener.unLoading()
                    selectRoleDialog.show()
                }
            }
        })
    }

    /**
     * Only log into the external account (username/password login).
     * JUST DO IT!!!
     */
    fun justLogin(context: Context, account: MinecraftAccount) {
        // No matching id found.
        fun roleNotFound() {
            TaskExecutors.runInUIThread {
                listener.onFailed(context.getString(R.string.other_login_role_not_found))
            }
        }

        login(context, object : LoginAccountListener {
            override fun onlyOneRole(authResult: AuthResult) {
                if (authResult.selectedProfile.id != account.profileId) {
                    roleNotFound()
                    return
                }
                writeAccount(account, authResult, authResult.selectedProfile.name, authResult.selectedProfile.id)
                TaskExecutors.runInUIThread {
                    listener.unLoading()
                    listener.onSuccess(account)
                }
            }

            override fun hasMultipleRoles(authResult: AuthResult) {
                authResult.availableProfiles.forEach { profile ->
                    if (profile.id == account.profileId) {
                        // If the ID matches the current account, this profile belongs to it.
                        writeAccount(account, authResult, profile.name, profile.id)
                        TaskExecutors.runInUIThread {
                            listener.unLoading()
                            listener.onSuccess(account)
                        }
                        return
                    }
                }
                roleNotFound()
            }
        })
    }

    private fun refresh(context: Context, account: MinecraftAccount) {
        Task.runTask {
            OtherLoginApi.setBaseUrl(baseUrl)
            OtherLoginApi.refresh(context, account, true, object : OtherLoginApi.Listener {
                override fun onSuccess(authResult: AuthResult) {
                    account.accessToken = authResult.accessToken
                    account.updateOtherSkin()
                    TaskExecutors.runInUIThread {
                        listener.unLoading()
                        listener.onSuccess(account)
                    }
                }

                override fun onFailed(error: String) {
                    TaskExecutors.runInUIThread {
                        listener.unLoading()
                        listener.onFailed(error)
                    }
                }
            })
        }.beforeStart(TaskExecutors.getAndroidUI()) {
            listener.onLoading()
        }.onThrowable { e ->
            val message = "An exception was encountered while performing the refresh task."
            Logging.e("Other Login", message, e)
            TaskExecutors.runInUIThread {
                listener.onFailed(e.message ?: message)
            }
        }.execute()
    }

    interface OnLoginListener {
        fun onLoading()
        fun unLoading()
        fun onSuccess(account: MinecraftAccount)
        fun onFailed(error: String)
    }

    /**
     * Login decisions taken depending on how many profiles the account owns.
     */
    private interface LoginAccountListener {
        fun onlyOneRole(authResult: AuthResult)

        fun hasMultipleRoles(authResult: AuthResult)
    }
}