package com.endiq.turtlelauncher.presentation

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.GZIPInputStream
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.R
import coil.ImageLoader
import coil.request.ImageRequest
import com.endiq.turtlelauncher.data.setting.Setting
import com.endiq.turtlelauncher.data.setting.SettingManager
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.data.update.GithubUpdateChecker
import com.endiq.turtlelauncher.domain.model.DownloadPhase
import com.endiq.turtlelauncher.domain.model.Instance
import com.endiq.turtlelauncher.domain.model.McVersion
import com.endiq.turtlelauncher.domain.model.MinecraftSupport
import com.endiq.turtlelauncher.presentation.base.BaseActivity
import com.endiq.turtlelauncher.presentation.main.MainViewModel
import com.endiq.turtlelauncher.presentation.ui.motion.TurtleMotion
import com.endiq.turtlelauncher.launch.AutoSettingsOptimizer
import com.endiq.turtlelauncher.plugins.feature.FeaturePluginManager
import com.endiq.turtlelauncher.feature.turtle.DailyPlaytimeStats
import com.endiq.turtlelauncher.feature.turtle.WeeklyPlaytimeChartView

/**
 * Turtle Launcher home entry point.
 *
 * The home screen deliberately inflates the original Turtle XML layout instead of recreating
 * it in Compose. The existing repositories and ViewModel stay in charge of accounts, versions,
 * installation, launch preparation, files, renderers, and low-end settings; this activity only
 * connects those flows to the original view IDs and navigation affordances.
 */
@AndroidEntryPoint
class MainActivity : BaseActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var launcherSetting = Setting()
    private var currentVersion: McVersion? = null
    private var currentInstances: List<Instance> = emptyList()

    private val minecraftLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val jarPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            startActivity(Intent(this, ShareImportActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "application/java-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(this, it.message ?: "Unable to import file", Toast.LENGTH_SHORT).show()
        }
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            result.data?.getStringExtra(LoginActivity.RESULT_ERROR)?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        } else if (result.resultCode == RESULT_OK) {
            viewModel.refreshLoginState()
        }
    }

    override fun onCreated() {
        // Apply the old Turtle automatic renderer/performance policy before the first launch.
        // It only changes persisted game settings; the original Turtle home remains untouched.
        AutoSettingsOptimizer.apply(this, MinecraftSupport.SUPPORTED_VERSION)
        launcherSetting = SettingManager.load(this)
        TurtleMotion.configure(launcherSetting.animationsEnabled)
        hideNavigation()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // This is the original Turtle home XML, including its legacy assets, spacing, panels,
        // version card, launcher rail, and right-side launch panel.
        setContentView(R.layout.fragment_launcher)
        bindOriginalTurtleHome()
        refreshHomeDashboard()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.versions.collect { versions ->
                        currentVersion = versions.firstOrNull { it.id == MinecraftSupport.SUPPORTED_VERSION }
                            ?: versions.firstOrNull()
                        updateVersionCard()
                        refreshHomeDashboard()
                    }
                }
                launch {
                    viewModel.instances.collect { instances ->
                        currentInstances = instances
                        updateVersionCard()
                        refreshHomeDashboard()
                    }
                }
                launch {
                    viewModel.progress.collect { updateProgress(it.phase, it.percent) }
                }
                launch {
                    viewModel.installError.collect { error ->
                        if (error != null) {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                            viewModel.clearInstallError()
                        }
                    }
                }
                launch {
                    viewModel.session.collect { session ->
                        val accountButton = findViewById<ImageButton>(R.id.top_bar_account_button)
                        accountButton?.contentDescription = session?.username ?: getString(R.string.main_nav_account)
                        updateAccountAvatar(session?.uuid)
                    }
                }
            }
        }

        // Preserve the existing in-app update check without changing the Turtle home layout.
        lifecycleScope.launch(Dispatchers.IO) {
            val skipped = SettingManager.load(this@MainActivity).skippedUpdateVersion
            val release = GithubUpdateChecker.fetchUpdate(BuildConfig.VERSION_NAME)
            if (release != null && release.tagName != skipped) {
                withContext(Dispatchers.Main) {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.update_available_title))
                        .setMessage(release.name.ifBlank { release.tagName })
                        .setNegativeButton(getString(R.string.later_button), null)
                        .setPositiveButton(getString(R.string.main_nav_download)) { _, _ ->
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))) }
                        }
                        .setNeutralButton(getString(R.string.skip_button)) { _, _ ->
                            SettingManager.save(
                                this@MainActivity,
                                SettingManager.load(this@MainActivity)
                                    .copy(skippedUpdateVersion = release.tagName),
                            )
                        }
                        .show()
                }
            }
        }

        // ViewModel emits a prepared launch only after all files/native payloads are ready.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.launchEvents.collect { params ->
                    MinecraftActivity.startForResult(
                        this@MainActivity,
                        minecraftLauncher,
                        versionId = params.versionId,
                        assetIndex = params.assetIndexId,
                        extraJars = params.extraJars,
                        mainClass = params.mainClass,
                        instanceDir = params.instanceDirPath,
                    )
                }
            }
        }
    }

    private fun bindOriginalTurtleHome() {
        val login = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) }
        val openFiles = { FileManagerActivity.start(this) }
        val openContents = { ContentPackBrowserActivity.start(this) }
        val openSettings = { SettingsActivity.start(this) }
        val openControls = { KeyboardLayoutEditorActivity.start(this) }
        val openAssistant = { AssistantActivity.start(this) }

        // Preserve the complete original Turtle navigation rail, but route each action to the
        // current activity so account, files, downloads, controls, and settings share state.
        findViewById<View>(R.id.top_bar_account_button).setOnClickListener { login() }
        findViewById<View>(R.id.top_bar_storage_button).setOnClickListener { openFiles() }
        findViewById<View>(R.id.top_bar_download_button).setOnClickListener { openContents() }
        findViewById<View>(R.id.top_bar_cursor_button).setOnClickListener { openControls() }
        findViewById<View>(R.id.top_bar_ai_button).setOnClickListener { openAssistant() }
        findViewById<View>(R.id.top_bar_settings_button).setOnClickListener { openSettings() }
        findViewById<View>(R.id.top_bar_tasks_button).setOnClickListener { showTaskStatus() }

        findViewById<View>(R.id.about_button).setOnClickListener { showAbout() }
        findViewById<View>(R.id.custom_control_button).setOnClickListener { openControls() }
        findViewById<View>(R.id.install_jar_button).setOnClickListener {
            jarPicker.launch(arrayOf("application/java-archive", "application/zip", "application/octet-stream"))
        }
        findViewById<View>(R.id.share_logs_button).setOnClickListener { openLatestLog() }
        findViewById<View>(R.id.modpack_import_button).setOnClickListener { openContents() }
        findViewById<View>(R.id.terracotta_button).setOnClickListener {
            TerracottaActivity.start(this, viewModel.session.value?.username)
        }
        findViewById<View>(R.id.last_log_card).setOnClickListener { openLatestLog() }

        // Keep the exact community destinations from the old Turtle home.
        findViewById<View>(R.id.link_discord_button).setOnClickListener {
            openUrl("https://discord.gg/8TfuMhM8tD")
        }
        findViewById<View>(R.id.link_website_button).setOnClickListener {
            openUrl("https://endiq-jar.github.io/endiq-shop/")
        }
        findViewById<View>(R.id.link_youtube_button).setOnClickListener {
            openUrl("https://youtube.com/@endiq-jar?si=9sb9OnKDJG2kUnO1")
        }

        findViewById<View>(R.id.version).setOnClickListener {
            currentVersion?.let(viewModel::selectVersion)
        }
        findViewById<View>(R.id.manager_profile_button).setOnClickListener {
            currentInstances.firstOrNull()?.let {
                InstanceSettingsActivity.start(this, it.id, it.name)
            } ?: openSettings()
        }
        findViewById<Button>(R.id.play_button).setOnClickListener {
            val instance = currentInstances.firstOrNull()
            if (instance != null) viewModel.launchInstance(instance)
            else currentVersion?.let(viewModel::downloadVanilla)
                ?: Toast.makeText(this, getString(R.string.version_no_versions), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.footer_github_button).setOnClickListener {
            openUrl("https://github.com/Endiq-jar/TurtleLauncher")
        }
        findViewById<View>(R.id.footer_version_text).setOnClickListener { showAbout() }
    }

    private fun updateAccountAvatar(uuid: String?) {
        val button = findViewById<ImageButton>(R.id.top_bar_account_button) ?: return
        if (uuid.isNullOrBlank()) {
            button.setImageResource(R.drawable.ic_add)
            button.imageTintList = androidx.core.content.ContextCompat.getColorStateList(
                this,
                R.color.turtle_text_secondary,
            )
            return
        }
        button.imageTintList = null
        button.setImageResource(R.drawable.ic_help)
        val cleanUuid = uuid.replace("-", "")
        val request = ImageRequest.Builder(this)
            .data("https://mc-heads.net/avatar/$cleanUuid/64")
            .target(
                onSuccess = { drawable -> button.setImageDrawable(drawable) },
                onError = { button.setImageResource(R.drawable.ic_help) },
            )
            .build()
        ImageLoader(this).enqueue(request)
    }

    private fun refreshHomeDashboard() {
        val chart = findViewById<WeeklyPlaytimeChartView>(R.id.stats_chart)
        chart?.setData(DailyPlaytimeStats.thisWeekMs(this).map { it / 3_600_000f }.toFloatArray())

        findViewById<TextView>(R.id.footer_app_name)?.text = getString(R.string.app_name)
        findViewById<TextView>(R.id.footer_version_text)?.text = "v${BuildConfig.VERSION_NAME}"

        val log = latestLogFile()
        val preview = findViewById<TextView>(R.id.last_log_preview)
        preview?.text = log?.let { readLogPreview(it) } ?: getString(R.string.main_last_log_none)

        val pluginContainer = findViewById<android.view.ViewGroup>(R.id.feature_plugins_container)
        val plugins = FeaturePluginManager.scan(this)
        pluginContainer?.removeAllViews()
        pluginContainer?.visibility = if (plugins.isEmpty()) View.GONE else View.VISIBLE
        plugins.forEach { plugin ->
            val item = layoutInflater.inflate(R.layout.item_feature_plugin, pluginContainer, false)
            item.findViewById<TextView>(R.id.feature_plugin_title)?.text = plugin.displayName
            item.findViewById<TextView>(R.id.feature_plugin_desc)?.text = plugin.description
            item.findViewById<ImageView>(R.id.feature_plugin_icon)?.setImageDrawable(
                packageManager.getApplicationIcon(plugin.applicationInfo),
            )
            item.setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage(plugin.packageName)
                if (launchIntent == null) {
                    Toast.makeText(this, "Unable to open feature", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching { startActivity(launchIntent) }
                        .onFailure { Toast.makeText(this, it.message ?: "Unable to open feature", Toast.LENGTH_SHORT).show() }
                }
            }
            pluginContainer?.addView(item)
        }
    }

    private fun latestLogFile(): File? {
        val roots = currentInstances.firstOrNull()?.let { instance ->
            val dir = InstanceManager.instanceDir(this, instance.id)
            listOf(File(dir, "logs"), File(dir, ".minecraft/logs"))
        }.orEmpty()
        return roots.asSequence()
            .flatMap { root -> root.listFiles()?.asSequence().orEmpty() }
            .filter { it.isFile && (it.extension == "log" || it.extension == "gz") }
            .maxByOrNull { it.lastModified() }
    }

    private fun readLogPreview(file: File): String = runCatching {
        if (file.extension == "gz") {
            GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
                lines.toList().takeLast(6).joinToString("\\n").takeLast(900)
            }
        } else {
            file.readLines().takeLast(6).joinToString("\\n").takeLast(900)
        }
    }.getOrDefault(getString(R.string.main_last_log_none))

    private fun openLatestLog() {
        val instance = currentInstances.firstOrNull()
        if (instance == null) {
            Toast.makeText(this, getString(R.string.main_last_log_none), Toast.LENGTH_SHORT).show()
            return
        }
        CrashReportActivity.start(this, InstanceManager.instanceDir(this, instance.id).absolutePath)
    }

    private fun showTaskStatus() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_tasks_dialog_title))
            .setMessage(
                if (viewModel.progress.value.phase == DownloadPhase.IDLE) {
                    getString(R.string.main_tasks_dialog_empty)
                } else {
                    "${viewModel.progress.value.phase}: ${viewModel.progress.value.percent}%"
                },
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAbout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_tab, getString(R.string.app_name)))
            .setMessage("${getString(R.string.app_name)}\\n${BuildConfig.VERSION_NAME}")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, it.message ?: "Unable to open link", Toast.LENGTH_SHORT).show() }
    }

    private fun updateVersionCard() {
        val versionName = findViewById<TextView>(R.id.version_name) ?: return
        val versionInfo = findViewById<TextView>(R.id.version_info) ?: return
        val play = findViewById<Button>(R.id.play_button) ?: return
        val instance = currentInstances.firstOrNull()
        val version = currentVersion

        versionName.text = instance?.mcVersion ?: version?.id ?: getString(R.string.version_no_versions)
        versionInfo.text = if (instance != null) instance.name else getString(R.string.main_version_release)
        versionInfo.visibility = View.VISIBLE
        play.isEnabled = version != null || instance != null
        play.text = getString(if (instance != null) R.string.main_launch else R.string.main_install)
    }

    private fun updateProgress(phase: DownloadPhase, percent: Int) {
        val play = findViewById<Button>(R.id.play_button) ?: return
        val badge = findViewById<TextView>(R.id.top_bar_tasks_badge)
        val running = phase != DownloadPhase.IDLE && phase != DownloadPhase.DONE && phase != DownloadPhase.ERROR
        badge?.visibility = if (running) View.VISIBLE else View.GONE
        if (running) badge?.text = "1"
        when (phase) {
            DownloadPhase.IDLE, DownloadPhase.DONE -> updateVersionCard()
            DownloadPhase.ERROR -> play.isEnabled = true
            else -> {
                play.isEnabled = false
                play.text = "${getString(R.string.downloading)} $percent%"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.clearLaunchingPopup()
    }

    override fun onResume() {
        super.onResume()
        launcherSetting = SettingManager.load(this)
        TurtleMotion.configure(launcherSetting.animationsEnabled)
        viewModel.refreshLoginState()
        viewModel.refreshInstances()
    }
}
