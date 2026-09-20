package com.endiq.turtlelauncher.ui.activity

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.task.TaskExecutors
import java.io.File

class ConfigEditorActivity : BaseActivity() {

    companion object {
        const val EXTRA_VERSION_NAME = "version_name"

        const val EXTRA_TARGET_FILE_PATH = "target_file_path"

        /** Above this size a file is skipped from the listing entirely (avoid OOM on huge files). */
        private const val MAX_LISTED_FILE_BYTES = 8_000_000L

        /** Hard cap on how many files get collected, so huge modpacks/worlds stay snappy. */
        private const val MAX_FILES_COLLECTED = 2000

        /** How many leading bytes to sniff when deciding if a file looks binary. */
        private const val BINARY_SNIFF_BYTES = 8000

        /** Largest file loaded fully and editable; anything bigger is a read-only truncated preview. */
        private const val MAX_EDIT_BYTES = 256 * 1024
    }

    private lateinit var fileList: ListView
    private lateinit var editorText: EditText
    private lateinit var currentFileLabel: TextView
    private lateinit var saveButton: Button
    private var currentFile: File? = null
    private var files: List<File> = emptyList()
    private var readOnly = false
    private var dirty = false
    private var suppressWatcher = false
    private var loadGeneration = 0

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

        (editorText as? AppCompatEditText)?.setEmojiCompatEnabled(false)
        editorText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher) dirty = true
            }
        })

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

        val gameDir = version.getGameDir()
        TaskExecutors.getDefault().execute {
            val collected = collectEditableFiles(gameDir)
            val labels = collected.map { it.relativeTo(gameDir).path }
            val first = collected.firstOrNull { it.length() <= MAX_EDIT_BYTES && !looksBinary(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                files = collected
                fileList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                fileList.setOnItemClickListener { _, _, position, _ -> openFile(files[position]) }
                saveButton.setOnClickListener { saveCurrentFile() }
                first?.let { loadFile(it) }
            }
        }
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
                .setPositiveButton(R.string.generic_ok) { _, _ -> loadFile(file, forceReadOnly = true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        loadFile(file)
    }

    private fun loadFile(file: File, forceReadOnly: Boolean = false) {
        saveCurrentFile(silent = true)
        val generation = ++loadGeneration
        currentFile = null
        dirty = false
        currentFileLabel.text = file.name
        saveButton.isEnabled = false
        TaskExecutors.getDefault().execute {
            val size = file.length()
            val truncated = size > MAX_EDIT_BYTES
            val text = runCatching { readCapped(file, MAX_EDIT_BYTES) }.getOrDefault("")
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                readOnly = forceReadOnly || truncated
                suppressWatcher = true
                editorText.filters = emptyArray()
                editorText.setText(text)
                editorText.setSelection(0)
                suppressWatcher = false
                dirty = false
                if (readOnly) {
                    editorText.filters = arrayOf(InputFilter { _, _, _, dest, dStart, dEnd -> dest.subSequence(dStart, dEnd) })
                }
                editorText.setShowSoftInputOnFocus(!readOnly)
                saveButton.isEnabled = !readOnly
                currentFile = file
                currentFileLabel.text = when {
                    truncated -> file.name + " (read-only preview, first " + (MAX_EDIT_BYTES / 1024) + " KB)"
                    forceReadOnly -> file.name + " (read-only)"
                    else -> file.name
                }
            }
        }
    }

    private fun readCapped(file: File, limit: Int): String {
        file.inputStream().use { stream ->
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val n = stream.read(buffer, total, limit - total)
                if (n <= 0) break
                total += n
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    private fun saveCurrentFile(silent: Boolean = false) {
        val file = currentFile ?: return
        if (readOnly) {
            if (!silent) Toast.makeText(this, "Read-only preview", Toast.LENGTH_SHORT).show()
            return
        }
        if (silent && !dirty) return
        val ok = runCatching { file.writeText(editorText.text.toString()) }.isSuccess
        if (ok) dirty = false
        if (!silent) {
            Toast.makeText(this, if (ok) getString(R.string.generic_save) + " ✓" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentFile(silent = true)
    }
}
