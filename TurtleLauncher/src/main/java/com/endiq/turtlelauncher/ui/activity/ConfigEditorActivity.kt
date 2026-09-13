package com.endiq.turtlelauncher.ui.activity

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.version.VersionsManager
import java.io.File

/**
 * TurtleLauncher v10: in-launcher text editor for a version's files. Any file/extension
 * can be opened — not just known config types. A flat file list + a monospace text box,
 * since the goal is "fix a file without leaving the app", not a full IDE. Files that look
 * binary are still shown but require a confirmation before opening, since editing them as
 * text and saving would corrupt them.
 */
class ConfigEditorActivity : BaseActivity() {

    companion object {
        const val EXTRA_VERSION_NAME = "version_name"

        /**
         * Opens directly on a single arbitrary file (e.g. from the general file browser's
         * "Edit" button) instead of listing a version's whole game dir. When set, the file
         * list only shows this one file and EXTRA_VERSION_NAME is ignored.
         */
        const val EXTRA_TARGET_FILE_PATH = "target_file_path"

        /** Above this size a file is skipped from the listing entirely (avoid OOM on huge files). */
        private const val MAX_LISTED_FILE_BYTES = 8_000_000L

        /** Hard cap on how many files get collected, so huge modpacks/worlds stay snappy. */
        private const val MAX_FILES_COLLECTED = 2000

        /** How many leading bytes to sniff when deciding if a file looks binary. */
        private const val BINARY_SNIFF_BYTES = 8000
    }

    private lateinit var fileList: ListView
    private lateinit var editorText: EditText
    private lateinit var currentFileLabel: TextView
    private lateinit var saveButton: Button
    private var currentFile: File? = null
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        val toolbar = findViewById<Toolbar>(R.id.config_editor_toolbar)
        toolbar.title = getString(R.string.setting_config_editor_title)
        toolbar.setNavigationOnClickListener { finish() }

        fileList = findViewById(R.id.config_file_list)
        editorText = findViewById(R.id.config_editor_text)
        currentFileLabel = findViewById(R.id.config_current_file_label)
        saveButton = findViewById(R.id.config_editor_save)

        val targetFilePath = intent.getStringExtra(EXTRA_TARGET_FILE_PATH)
        if (targetFilePath != null) {
            val targetFile = File(targetFilePath)
            if (!targetFile.isFile) {
                Toast.makeText(this, R.string.dependency_graph_no_version, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            files = listOf(targetFile)
            fileList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(targetFile.name))
            fileList.setOnItemClickListener { _, _, position, _ -> openFile(files[position]) }
            saveButton.setOnClickListener { saveCurrentFile() }
            openFile(targetFile)
            return
        }

        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME)
        val version = versionName?.let { name -> VersionsManager.getVersions().find { it.getVersionName() == name } }
            ?: VersionsManager.getCurrentVersion()

        if (version == null) {
            Toast.makeText(this, R.string.dependency_graph_no_version, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        files = collectEditableFiles(version.getGameDir())
        fileList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            files.map { it.relativeTo(version.getGameDir()).path }
        )
        fileList.setOnItemClickListener { _, _, position, _ -> openFile(files[position]) }

        saveButton.setOnClickListener { saveCurrentFile() }

        // Auto-open the first non-binary file so we don't greet the user with a warning
        // dialog on entry; if everything looks binary, just leave the editor empty.
        files.firstOrNull { !looksBinary(it) }?.let { loadFile(it) }
    }

    /**
     * Walks the game dir up to a shallow depth collecting every file (any extension),
     * skipping only save-world data (huge, not meaningfully "editable" as text) and
     * screenshots (binary images with no value here). Everything else — mods,
     * resourcepacks, shaderpacks, logs, any config type — is included.
     */
    private fun collectEditableFiles(gameDir: File, maxDepth: Int = 4): List<File> {
        if (!gameDir.isDirectory) return emptyList()
        val result = mutableListOf<File>()

        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || result.size >= MAX_FILES_COLLECTED) return
            val children = dir.listFiles() ?: return
            children.forEach { child ->
                if (result.size >= MAX_FILES_COLLECTED) return@forEach
                when {
                    child.isDirectory -> {
                        if (child.name !in setOf("saves", "screenshots")) {
                            walk(child, depth + 1)
                        }
                    }
                    child.length() in 0 until MAX_LISTED_FILE_BYTES -> {
                        result.add(child)
                    }
                }
            }
        }
        walk(gameDir, 0)
        return result.sortedBy { it.name }
    }

    /** Sniffs the leading bytes of a file for NUL bytes / high non-printable ratio. */
    private fun looksBinary(file: File): Boolean {
        return runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(minOf(BINARY_SNIFF_BYTES, file.length().toInt().coerceAtLeast(1)))
                val read = stream.read(buffer)
                if (read <= 0) return@runCatching false
                var suspicious = 0
                for (i in 0 until read) {
                    val b = buffer[i].toInt()
                    if (b == 0) return@runCatching true // NUL byte: treat as binary immediately
                    val printable = b in 0x09..0x0D || b in 0x20..0x7E || b < 0
                    if (!printable) suspicious++
                }
                suspicious.toDouble() / read > 0.3
            }
        }.getOrDefault(false)
    }

    private fun openFile(file: File) {
        if (looksBinary(file)) {
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(R.string.config_editor_binary_warning)
                .setPositiveButton(R.string.generic_ok) { _, _ -> loadFile(file) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        loadFile(file)
    }

    private fun loadFile(file: File) {
        if (currentFile != null) saveCurrentFile(silent = true)
        currentFile = file
        currentFileLabel.text = file.name
        editorText.setText(runCatching { file.readText() }.getOrDefault(""))
    }

    private fun saveCurrentFile(silent: Boolean = false) {
        val file = currentFile ?: return
        val ok = runCatching { file.writeText(editorText.text.toString()) }.isSuccess
        if (!silent) {
            Toast.makeText(this, if (ok) getString(R.string.generic_save) + " ✓" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentFile(silent = true)
    }
}
