package com.endiq.turtlelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import com.endiq.turtlelauncher.presentation.base.BaseActivity
import com.endiq.turtlelauncher.presentation.ui.screen.AssistantScreen
import com.endiq.turtlelauncher.presentation.ui.theme.TurtleLauncherTheme

class AssistantActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, AssistantActivity::class.java))
    }

    override fun onCreated() {
        setContent { TurtleLauncherTheme { AssistantScreen(onBack = { finish() }) } }
    }
}
