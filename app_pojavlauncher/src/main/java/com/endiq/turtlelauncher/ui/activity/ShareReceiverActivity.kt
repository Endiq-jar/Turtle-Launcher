package com.endiq.turtlelauncher.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import net.kdt.pojavlaunch.LauncherActivity
import java.io.File

class ShareReceiverActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val sharedText = extractSharedLog(intent)
        if (sharedText.isNullOrBlank()) {
            Toast.makeText(this, R.string.assistant_share_nothing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val savedFile = saveSharedLog(sharedText)
        if (savedFile == null) {
            Toast.makeText(this, R.string.assistant_share_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Hand the log to the Assistant inside the main launcher UI.
        val launchIntent = Intent(this, LauncherActivity::class.java).apply {
            putExtra(LauncherActivity.EXTRA_OPEN_ASSISTANT, true)
            putExtra(LauncherActivity.EXTRA_SHARED_LOG_PATH, savedFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(launchIntent)
        finish()
    }

    /**
     * Reads whatever the sending app shared - plain text first (some apps share the log
     * content itself), then a shared file. Returns null when nothing usable came through
     * (unsupported payload, unreadable file, or binary data that can't be a log).
     */
    private fun extractSharedLog(intent: Intent): String? {
        val directText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!directText.isNullOrBlank()) return directText.take(MAX_SHARED_CHARS)

        val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: return null
        return readSharedStream(streamUri)
    }

    private fun readSharedStream(uri: Uri): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (total < MAX_SHARED_BYTES) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    total += read
                }
                val text = output.toString(Charsets.UTF_8.name())
                // A log that contains NUL bytes is binary (e.g. a zipped bundle) - the
                // Assistant can't read that, so reject it instead of showing garbage.
                if (text.contains('\u0000')) null else text.take(MAX_SHARED_CHARS)
            }
        }.onFailure { e ->
            Logging.e(TAG, "Could not read the shared log stream", e)
        }.getOrNull()
    }

    private fun saveSharedLog(text: String): File? {
        return runCatching {
            val dir = File(PathManager.DIR_LAUNCHER_LOG)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, SHARED_LOG_FILE_NAME)
            file.writeText(text)
            file
        }.onFailure { e ->
            Logging.e(TAG, "Could not save the shared log", e)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ShareReceiverActivity"

        /** Name of the persisted copy inside DIR_LAUNCHER_LOG. */
        const val SHARED_LOG_FILE_NAME = "shared_log.txt"

        /** Shared payloads are capped: logs beyond this are truncated from the old end by
         *  the Assistant itself; the cap here only protects the copy/read step. */
        private const val MAX_SHARED_CHARS = 1_000_000
        private const val MAX_SHARED_BYTES = 2 * 1024 * 1024
    }
}
