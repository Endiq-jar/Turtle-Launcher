package com.endiq.turtlelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import com.endiq.turtlelauncher.presentation.base.BaseActivity
import com.endiq.turtlelauncher.presentation.ui.components.TerracottaEntry
import com.endiq.turtlelauncher.presentation.ui.theme.TurtleLauncherTheme

/** Full-screen host for the original Turtle home Terracotta quick action. */
class TerracottaActivity : BaseActivity() {
    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            TurtleLauncherTheme {
                TerracottaEntry(
                    activity = this,
                    userName = intent.getStringExtra(EXTRA_USERNAME),
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_USERNAME = "username"

        fun start(context: Context, username: String?) {
            context.startActivity(Intent(context, TerracottaActivity::class.java).apply {
                putExtra(EXTRA_USERNAME, username)
            })
        }
    }
}
