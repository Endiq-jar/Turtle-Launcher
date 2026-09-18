package com.endiq.turtlelauncher.ui.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.SettingsFragmentEmotesBinding
import com.endiq.turtlelauncher.feature.emotes.Emotes
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.KeyboardDialog
import com.endiq.turtlelauncher.utils.ZHTools
import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode
import net.kdt.pojavlaunch.utils.DownloadUtils
import java.io.File

/**
 * Settings -> Emotes.
 *
 * TurtleLauncher: the launcher-side half of the emote feature (the in-game half is the
 * Emotes row in the game menu - see view_game_menu.xml / MainActivity's
 * MenuSettingsInitListener). Embeds the official Emotecraft community library
 * (emotes.kosmx.dev) in a WebView and intercepts its downloads, saving .emote files
 * straight into the current version's `<gameDir>/emotes` folder - the folder Emotecraft
 * reads at startup, so a download here is usable in game without any file juggling.
 *
 * Also hosts:
 *  - the Emotecraft mod status for the current version (emotes need the mod, installed
 *    separately by the user),
 *  - the emote-wheel key picker (the key the in-game "Play emote" button sends; default B
 *    matches Emotecraft's own default wheel keybind), reusing KeyboardDialog so the picker
 *    offers exactly the keys the launcher can send.
 *
 * Everything that can fail (WebView init, site load, downloads, file scans) is wrapped:
 * this screen must never be able to crash the launcher, mirroring the crash-audit rules
 * applied across the codebase.
 */
class EmotesFragment : FragmentWithAnim(R.layout.settings_fragment_emotes) {
    companion object {
        const val TAG: String = "EmotesFragment"
    }

    private lateinit var binding: SettingsFragmentEmotesBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentEmotesBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.subSettingsBackButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

        binding.openBrowserButton.setOnClickListener {
            ZHTools.openLink(requireContext(), Emotes.EMOTE_SITE_URL)
        }
        binding.installEmotecraftButton.setOnClickListener {
            handleInstallEmotecraft()
        }
        binding.emotesWheelKeyRow.setOnClickListener { showWheelKeyPicker() }

        // Nested-scroll workaround (see settings_fragment_emotes.xml's comment on these
        // two FABs): page the WebView's own content directly rather than relying on swipe
        // gestures, which the outer NestedScrollView can steal mid-scroll.
        binding.emotesPagePrevButton.setOnClickListener {
            runCatching { binding.emotesWebView.pageUp(false) }
        }
        binding.emotesPageNextButton.setOnClickListener {
            runCatching { binding.emotesWebView.pageDown(false) }
        }

        refreshStatus()
        setupWebView()
    }

    private fun handleInstallEmotecraft() {
        val (_, loader) = Emotes.getCurrentVersionInfo()
        if (loader == null) {
            Toast.makeText(requireContext(), R.string.emotes_install_no_loader, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(requireContext(), R.string.emotes_installing, Toast.LENGTH_SHORT).show()
        binding.installEmotecraftButton.isEnabled = false

        Task.runTask {
            Emotes.autoInstallEmotecraft()
        }.ended(TaskExecutors.getAndroidUI()) { outcome ->
            if (!isAdded) return@ended
            binding.installEmotecraftButton.isEnabled = true
            // outcome lists what actually got installed (Emotecraft + its dependencies,
            // e.g. Player Animation Library) - show it, falling back to the generic
            // success string only if it came back empty.
            val message = outcome?.takeIf { it.isNotBlank() }
                ?: getString(R.string.emotes_install_success)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            refreshStatus()
        }.onThrowable { error ->
            TaskExecutors.runInUIThread {
                if (!isAdded) return@runInUIThread
                binding.installEmotecraftButton.isEnabled = true
                val msg = error.message ?: error.toString()
                Toast.makeText(requireContext(), getString(R.string.emotes_install_failed, msg), Toast.LENGTH_LONG).show()
            }
        }.execute()
    }

    override fun onResume() {
        super.onResume()
        // The user may have installed the mod or changed version while away.
        refreshStatus()
    }

    /** Mod status + save-folder caption. File scans are guarded inside Emotes. */
    private fun refreshStatus() {
        val emotecraftInstalled = Emotes.isEmotecraftInstalled()
        val animatorInstalled = Emotes.isPlayerAnimatorInstalled()
        // Three visible states: nothing installed, Emotecraft there but its mandatory
        // Player Animation Library dependency missing (game would crash on launch), and
        // everything in place. The install button doubles as "install the missing
        // dependency" in the middle state - autoInstallEmotecraft() is idempotent about
        // what's already present.
        val statusRes = when {
            emotecraftInstalled && animatorInstalled -> R.string.emotes_status_installed
            emotecraftInstalled -> R.string.emotes_status_missing_animator
            else -> R.string.emotes_status_missing
        }
        binding.emotesModStatus.setText(statusRes)
        val complete = emotecraftInstalled && animatorInstalled
        binding.installEmotecraftButton.visibility = if (complete) View.GONE else View.VISIBLE
        binding.installEmotecraftButton.setText(
            if (emotecraftInstalled) R.string.emotes_install_animator_button
            else R.string.emotes_install_button
        )
        val dir = runCatching { Emotes.emotesDir().absolutePath }.getOrDefault("(unavailable)")
        binding.emotesFolderInfo.text = getString(R.string.emotes_folder_info, dir)
        updateWheelKeyLabel()
    }

    private fun updateWheelKeyLabel() {
        val code = AllSettings.emoteWheelKeycode.getValue()
        binding.emotesWheelKeyValue.text = getString(R.string.emotes_wheel_key_value, glfwKeyName(code))
    }

    /** Human-readable name for a GLFW keycode, via the launcher's own key table. */
    private fun glfwKeyName(code: Int): String {
        val fallback = "0x${Integer.toHexString(code)}"
        return runCatching {
            val names = EfficientAndroidLWJGLKeycode.generateKeyName()
            for (i in names.indices) {
                if (EfficientAndroidLWJGLKeycode.getValueByIndex(i).toInt() == code) {
                    return@runCatching names[i] ?: fallback
                }
            }
            fallback
        }.getOrDefault(fallback)
    }

    /** Reuses KeyboardDialog (the same picker the control editor uses): its index maps
     *  straight onto the launcher's GLFW key table. */
    private fun showWheelKeyPicker() {
        runCatching {
            KeyboardDialog(requireContext())
                .setOnKeycodeSelectListener { index ->
                    val code = EfficientAndroidLWJGLKeycode.getValueByIndex(index).toInt()
                    AllSettings.emoteWheelKeycode.put(code).save()
                    updateWheelKeyLabel()
                }
                .show()
        }.onFailure { e ->
            Logging.e(TAG, "Could not open the emote wheel key picker", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        runCatching {
            binding.emotesWebView.settings.apply {
                // The emote library site is a modern web app - JS and DOM storage are
                // required for browsing/searching to work at all.
                javaScriptEnabled = true
                domStorageEnabled = true
            }
            // Keep navigation inside the WebView (no browser hand-off mid-browse).
            binding.emotesWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.emotesProgress.visibility = View.GONE
                }
            }
            binding.emotesWebView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.emotesProgress.visibility =
                        if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            // The heart of "download it here, use it in game": every download the site
            // offers is saved into <gameDir>/emotes instead of the system Download folder.
            binding.emotesWebView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                downloadEmote(url, contentDisposition, mimeType)
            }
            binding.emotesWebView.loadUrl(Emotes.EMOTE_SITE_URL)
        }.onFailure { e ->
            // A WebView that cannot initialize (missing provider on some ROMs) must not
            // take the launcher down - the Open-in-browser button still works.
            Logging.e(TAG, "WebView setup failed; use the browser button instead", e)
            Toast.makeText(context, R.string.emotes_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadEmote(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url.isNullOrEmpty()) return
        val context = context ?: return
        val fileName = runCatching { URLUtil.guessFileName(url, contentDisposition, mimeType) }
            .getOrNull() ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "emote.emote" }
        val destDir = runCatching { Emotes.emotesDir() }.getOrNull()
        if (destDir == null) {
            Toast.makeText(context, R.string.emotes_save_failed, Toast.LENGTH_LONG).show()
            return
        }
        val destFile = File(destDir, fileName)
        binding.emotesProgress.visibility = View.VISIBLE
        Task.runTask {
            destDir.mkdirs()
            DownloadUtils.downloadFile(url, destFile)
        }.ended(TaskExecutors.getAndroidUI()) {
            // The user may have navigated away while downloading - touching the binding
            // or a Toast with a null context must not crash (crash-audit rule).
            if (!isAdded) return@ended
            binding.emotesProgress.visibility = View.GONE
            Toast.makeText(context, getString(R.string.emotes_saved, fileName), Toast.LENGTH_LONG).show()
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                if (!isAdded) return@runInUIThread
                binding.emotesProgress.visibility = View.GONE
                Toast.makeText(context, R.string.emotes_save_failed, Toast.LENGTH_LONG).show()
                Logging.e(TAG, "Emote download failed: $url", e)
            }
        }.execute()
    }

    override fun onDestroyView() {
        // WebViews hold native resources and a reference to their container - tear it down
        // explicitly so leaving the screen can't leak (or crash on re-inflation).
        runCatching {
            binding.emotesWebView.stopLoading()
            (binding.emotesWebView.parent as? ViewGroup)?.removeView(binding.emotesWebView)
            binding.emotesWebView.destroy()
        }
        super.onDestroyView()
    }
}
