package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Toast
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogOtherLoginBinding
import com.endiq.turtlelauncher.feature.accounts.OtherLoginHelper
import com.endiq.turtlelauncher.feature.login.Servers.Server
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener
import com.endiq.turtlelauncher.utils.ZHTools


class OtherLoginDialog(
    context: Context,
    private val server: Server,
    private val listener: OtherLoginHelper.OnLoginListener
) : FullScreenDialog(context), View.OnClickListener, DialogInitializationListener {

    private val binding = DialogOtherLoginBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            serverName.text = server.serverName

            // ── Server type badge ────────────────────────────────────────────
            if (server.isNide8Auth) {
                serverTypeBadge.visibility = View.VISIBLE
                serverTypeBadge.text = context.getString(R.string.other_login_type_nide8)
                serverTypeBadge.setTextColor(0xFFFFAB40.toInt())
                // nide8auth uses email/password the same as authlib-injector
                emailText.setText(R.string.other_login_email)
            } else {
                serverTypeBadge.visibility = View.VISIBLE
                serverTypeBadge.text = context.getString(R.string.other_login_type_authlib)
                serverTypeBadge.setTextColor(0xFF00E5FF.toInt())
            }

            // ── ely.by quick-fill hint ───────────────────────────────────────
            val isElyBy = server.baseUrl?.contains("ely.by") == true ||
                          server.serverName?.contains("ely.by", ignoreCase = true) == true
            elybyHint.visibility = if (isElyBy) View.VISIBLE else View.GONE

            // ── Register link ────────────────────────────────────────────────
            if (server.register.isNullOrEmpty()) {
                registryText.visibility = View.GONE
            } else {
                registryText.setOnClickListener(this@OtherLoginDialog)
            }

            cancelButton.setOnClickListener(this@OtherLoginDialog)
            loginButton.setOnClickListener(this@OtherLoginDialog)
        }

        DraggableDialog.initDialog(this)
    }

    private fun checkAccountInformation(email: String?, password: String?): Boolean {
        val emailEmpty = email.isNullOrEmpty()
        val passwordEmpty = password.isNullOrEmpty()
        return if (emailEmpty || passwordEmpty) {
            val err = context.getString(R.string.generic_error_field_empty)
            if (emailEmpty) binding.emailEdit.error = err
            if (passwordEmpty) binding.passwordEdit.error = err
            false
        } else true
    }

    override fun onInit(): Window? = window

    override fun onClick(v: View) {
        binding.apply {
            when (v) {
                cancelButton -> dismiss()
                registryText -> {
                    server.register.takeIf { !it.isNullOrEmpty() }?.let { link ->
                        ZHTools.openLink(context, link)
                        dismiss()
                    }
                }
                loginButton -> {
                    val email = emailEdit.text.toString()
                    val password = passwordEdit.text.toString()
                    if (!checkAccountInformation(email, password)) return
                    if (server.baseUrl.isNullOrEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.other_login_server_not_empty),
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    OtherLoginHelper(
                        server.baseUrl, server.serverName,
                        email, password, listener
                    ).createNewAccount(context)
                    dismiss()
                }
            }
        }
    }
}
