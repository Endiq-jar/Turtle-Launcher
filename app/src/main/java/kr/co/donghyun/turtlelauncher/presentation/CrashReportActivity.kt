package kr.co.donghyun.turtlelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import kr.co.donghyun.turtlelauncher.presentation.base.BaseActivity
import kr.co.donghyun.turtlelauncher.presentation.crashreport.CrashReportViewModel
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.CrashReportScreen
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.*
import java.io.File

@AndroidEntryPoint
class CrashReportActivity : BaseActivity() {

    private val viewModel: CrashReportViewModel by viewModels()

    companion object {
        private const val EXTRA_INSTANCE_DIR = "instance_dir"

        fun start(context: Context, instanceDir: String) {
            context.startActivity(
                Intent(context, CrashReportActivity::class.java).apply {
                    putExtra(EXTRA_INSTANCE_DIR, instanceDir)
                }
            )
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )

        val instanceDir = intent.getStringExtra(EXTRA_INSTANCE_DIR) ?: run { finish(); return }
        viewModel.initialize(File(instanceDir))

        setContent {
            TurtleLauncherTheme {
                val logPath by viewModel.logPath.collectAsState()
                val logContent by viewModel.logContent.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                val suspects by viewModel.suspects.collectAsState()

                CrashReportScreen(
                    logPath = logPath,
                    logContent = logContent,
                    isLoading = isLoading,
                    suspects = suspects,
                    onBack = { finish() },
                    onToggleMod = { jarName, enable -> viewModel.toggleMod(jarName, enable) },
                )
            }
        }
    }
}
