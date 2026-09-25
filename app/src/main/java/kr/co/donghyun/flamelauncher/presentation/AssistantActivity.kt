package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.AssistantScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TurtleLauncherTheme

class AssistantActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, AssistantActivity::class.java))
    }

    override fun onCreated() {
        setContent { TurtleLauncherTheme { AssistantScreen(onBack = { finish() }) } }
    }
}
