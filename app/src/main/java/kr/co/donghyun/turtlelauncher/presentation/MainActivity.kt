package kr.co.donghyun.turtlelauncher.presentation

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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
import kr.co.donghyun.turtlelauncher.BuildConfig
import kr.co.donghyun.turtlelauncher.R
import kr.co.donghyun.turtlelauncher.data.setting.Setting
import kr.co.donghyun.turtlelauncher.data.setting.SettingManager
import kr.co.donghyun.turtlelauncher.data.update.GithubUpdateChecker
import kr.co.donghyun.turtlelauncher.domain.model.DownloadPhase
import kr.co.donghyun.turtlelauncher.domain.model.Instance
import kr.co.donghyun.turtlelauncher.domain.model.McVersion
import kr.co.donghyun.turtlelauncher.domain.model.MinecraftSupport
import kr.co.donghyun.turtlelauncher.presentation.base.BaseActivity
import kr.co.donghyun.turtlelauncher.presentation.main.MainViewModel
import kr.co.donghyun.turtlelauncher.presentation.ui.motion.TurtleMotion
import kr.co.donghyun.turtlelauncher.launch.AutoSettingsOptimizer

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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.versions.collect { versions ->
                        currentVersion = versions.firstOrNull { it.id == MinecraftSupport.SUPPORTED_VERSION }
                            ?: versions.firstOrNull()
                        updateVersionCard()
                    }
                }
                launch {
                    viewModel.instances.collect { instances ->
                        currentInstances = instances
                        updateVersionCard()
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
                        findViewById<ImageButton>(R.id.top_bar_account_button)?.contentDescription =
                            session?.username ?: getString(R.string.main_nav_account)
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

        // Original Turtle top bar.
        findViewById<View>(R.id.top_bar_account_button).setOnClickListener { login() }
        findViewById<View>(R.id.top_bar_storage_button).setOnClickListener { openFiles() }
        findViewById<View>(R.id.top_bar_download_button).setOnClickListener { openContents() }
        findViewById<View>(R.id.top_bar_cursor_button).setOnClickListener { openControls() }
        findViewById<View>(R.id.top_bar_ai_button).setOnClickListener { openAssistant() }
        findViewById<View>(R.id.top_bar_settings_button).setOnClickListener { openSettings() }
        findViewById<View>(R.id.top_bar_tasks_button).setOnClickListener { openFiles() }

        // Original Turtle quick-action rail.
        findViewById<View>(R.id.about_button).setOnClickListener { openContents() }
        findViewById<View>(R.id.custom_control_button).setOnClickListener { openControls() }
        findViewById<View>(R.id.install_jar_button).setOnClickListener { openFiles() }
        findViewById<View>(R.id.share_logs_button).setOnClickListener { openFiles() }
        findViewById<View>(R.id.modpack_import_button).setOnClickListener { openContents() }
        findViewById<View>(R.id.terracotta_button).setOnClickListener {
            Toast.makeText(this, getString(R.string.terracotta_enable), Toast.LENGTH_SHORT).show()
        }

        // There is exactly one supported release in this build, so the original version card
        // remains a selector without exposing an unsupported-version list.
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
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Endiq-jar/TurtleLauncher")))
            }
        }
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
