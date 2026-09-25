package com.endiq.turtlelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.presentation.base.BaseActivity
import com.endiq.turtlelauncher.presentation.ui.screen.FileBrowserScreen
import com.endiq.turtlelauncher.presentation.ui.theme.TurtleLauncherTheme
import java.io.File

class FileManagerActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, FileManagerActivity::class.java))
    }

    private lateinit var root: File
    private var current by mutableStateOf<File?>(null)

    override fun onCreated() {
        root = InstanceManager.instancesDir(this).also { it.mkdirs() }
        current = root
        setContent {
            TurtleLauncherTheme {
                current?.let { directory ->
                    FileBrowserScreen(
                        root = root,
                        directory = directory,
                        onNavigate = { next ->
                            // Only allow navigation below the instances root.
                            val safe = runCatching { next.canonicalPath.startsWith(root.canonicalPath) }.getOrDefault(false)
                            if (safe) current = next
                        },
                        onBack = { finish() },
                        onDelete = { target ->
                            val safe = runCatching { target.canonicalPath.startsWith(root.canonicalPath) }.getOrDefault(false)
                            if (safe) target.deleteRecursively()
                        },
                    )
                }
            }
        }
    }
}
