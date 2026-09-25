package kr.co.donghyun.turtlelauncher.presentation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import kr.co.donghyun.turtlelauncher.presentation.base.BaseActivity
import kr.co.donghyun.turtlelauncher.presentation.settings.SettingsViewModel
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.SettingsScreen
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TurtleLauncherTheme

@AndroidEntryPoint
class SettingsActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    private val viewModel: SettingsViewModel by viewModels()

    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.setBackgroundUri(uri.toString())
        }
    }

    private val skinPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importSkin(uri)
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / 1024 / 1024).toInt()
        val maxHeapCeilingMb = (totalRamMb / 256) * 256

        setContent {
            TurtleLauncherTheme {
                val settings by viewModel.settings.collectAsState()
                val saved by viewModel.saved.collectAsState()
                val globalRenderer by viewModel.globalRenderer.collectAsState()
                val launcherSetting by viewModel.launcherSetting.collectAsState()

                SettingsScreen(
                    onBack = { finish() },
                    settings = settings,
                    saved = saved,
                    globalRenderer = globalRenderer,
                    totalRamMb = totalRamMb,
                    maxHeapCeilingMb = maxHeapCeilingMb,
                    onSettingsChange = { viewModel.updateSettings(it) },
                    onReset = { viewModel.resetSettings() },
                    onSave = { viewModel.saveExplicitly() },
                    onGlobalRendererChange = { viewModel.setGlobalRenderer(it) },
                    launcherSetting = launcherSetting,
                    onAnimationsEnabledChange = { viewModel.setAnimationsEnabled(it) },
                    onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                    onClearBackground = { viewModel.setBackgroundUri(null) },
                    onPickSkin = { skinPicker.launch(arrayOf("image/png", "image/*")) },
                    onClearSkin = { viewModel.clearSkin() },
                )
            }
        }
    }
}
