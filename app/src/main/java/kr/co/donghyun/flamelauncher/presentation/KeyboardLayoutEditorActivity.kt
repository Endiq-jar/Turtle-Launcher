package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.keyboardeditor.KeyboardEditorViewModel
import kr.co.donghyun.flamelauncher.presentation.ui.screen.KeyboardLayoutEditorScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TurtleLauncherTheme

@AndroidEntryPoint
class KeyboardLayoutEditorActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, KeyboardLayoutEditorActivity::class.java))
        }
    }

    private val viewModel: KeyboardEditorViewModel by viewModels()

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )
        setContent {
            TurtleLauncherTheme {
                KeyboardLayoutEditorScreen(
                    onBack = { finish() },
                    initialButtons = viewModel.getInitialLayout(),
                    onSave = { viewModel.saveLayout(it) },
                )
            }
        }
    }
}
