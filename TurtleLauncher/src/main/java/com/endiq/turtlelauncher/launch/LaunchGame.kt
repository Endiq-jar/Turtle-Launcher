package com.endiq.turtlelauncher.launch

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.endiq.mcgui.ProgressLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.event.single.AccountUpdateEvent
import com.endiq.turtlelauncher.feature.accounts.AccountType
import com.endiq.turtlelauncher.feature.accounts.AccountUtils
import com.endiq.turtlelauncher.feature.accounts.AccountsManager
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.NanoVGNativesFix
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.setting.AllStaticSettings
import com.endiq.turtlelauncher.support.touch_controller.ControllerProxy
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.LifecycleAwareTipDialog
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.http.NetworkUtils
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.endiq.launcher.Architecture
import net.endiq.launcher.JMinecraftVersionList
import net.endiq.launcher.Logger
import net.endiq.launcher.Tools
import net.endiq.launcher.authenticator.microsoft.PresentedException
import net.endiq.launcher.lifecycle.ContextAwareDoneListener
import java.io.File
import net.endiq.launcher.multirt.MultiRTUtils
import net.endiq.launcher.plugins.FFmpegPlugin
import net.endiq.launcher.progresskeeper.ProgressKeeper
import net.endiq.launcher.services.GameService
import net.endiq.launcher.tasks.AsyncMinecraftDownloader
import net.endiq.launcher.tasks.MinecraftDownloader
import net.endiq.launcher.utils.JREUtils
import net.endiq.launcher.value.MinecraftAccount
import org.greenrobot.eventbus.EventBus

class LaunchGame {
    companion object {
        /**
         * Changed to happen before the game launches.
         * - perform the login while refreshing the account info right away (obviously more
         *   sensible, right, TurtleLauncher?)
         * - copy the options.txt file into the game directory
         * @param version the selected version
         */
        @JvmStatic
        fun preLaunch(context: Context, version: Version) {
            runCatching {
                val loader = version.getVersionInfo()?.loaderInfo?.firstNotNullOfOrNull {
                    com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils.getModLoader(it.name)
                }
                NanoVGNativesFix.ensureInstalled(context, loader, File(version.getGameDir(), "mods"))
            }.onFailure { e -> Logging.e("LaunchGame", "NanoVGNativesFix check failed", e) }

            runCatching {
                val rendererUniqueId = version.getRenderer()
                val rendererInstance = runCatching {
                    com.endiq.turtlelauncher.renderer.Renderers.getCompatibleRenderers(context).second
                        .firstOrNull { it.getUniqueIdentifier() == rendererUniqueId }
                }.getOrNull()
                val catalogEntry = com.endiq.turtlelauncher.renderer.RendererCatalog.get(
                    rendererInstance?.getRendererId() ?: rendererUniqueId
                )
                // Renderer plugin apps ship their own supported range in their manifest
                // meta-data (minMCVer/maxMCVer - e.g. the MobileGlues plugin declares
                // 1.17+); honor it exactly like a catalog range so an installed plugin is
                // treated like any first-class renderer.
                val plugin = com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager.getRendererList()
                    .find { it.uniqueIdentifier == rendererUniqueId }
                val maxVersion = catalogEntry?.maxMinecraftVersion
                    ?: plugin?.maxMinecraftVersion?.takeIf { it.isNotBlank() }
                val minVersion = catalogEntry?.minMinecraftVersion
                    ?: plugin?.minMinecraftVersion?.takeIf { it.isNotBlank() }
                val mcVersion = version.getVersionName()
                val exceedsMax = maxVersion != null &&
                        org.jackhuang.hmcl.util.versioning.VersionNumber.compare(mcVersion, maxVersion) > 0
                val belowMin = !exceedsMax && minVersion != null &&
                        org.jackhuang.hmcl.util.versioning.VersionNumber.compare(mcVersion, minVersion) < 0
                if (exceedsMax || belowMin) {
                    val boundary = if (exceedsMax) maxVersion else minVersion
                    val warningRes = if (exceedsMax) R.string.renderer_compat_launch_warning_max else R.string.renderer_compat_launch_warning_min
                    val displayName = rendererInstance?.getRendererName() ?: rendererUniqueId
                    Logging.w("LaunchGame", "Renderer $displayName is outside its documented range " +
                            "(max=$maxVersion, min=$minVersion) for MC $mcVersion - warning shown, launch not blocked")
                    Toast.makeText(context, context.getString(warningRes, displayName, boundary, mcVersion), Toast.LENGTH_LONG).show()
                }
            }.onFailure { e -> Logging.e("LaunchGame", "Renderer/MC-version compatibility check failed", e) }

            val networkAvailable = NetworkUtils.isNetworkAvailable(context)

            fun launch(setOfflineAccount: Boolean = false) {
                version.offlineAccountLogin = setOfflineAccount

                val versionName = version.getVersionName()
                val mcVersion = AsyncMinecraftDownloader.getListedVersion(versionName)
                val listener = ContextAwareDoneListener(context, version)
                // Without a network, skip the download tasks and launch directly.
                if (!networkAvailable) {
                    listener.onDownloadDone()
                } else {
                    MinecraftDownloader().start(mcVersion, versionName, listener)
                }
            }

            fun setGameProgress(pull: Boolean) {
                if (pull) {
                    ProgressKeeper.submitProgress(ProgressLayout.CHECKING_MODS, 0, R.string.mod_check_progress_message, 0, 0, 0)
                    ProgressKeeper.submitProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_downloading_game_files, 0, 0, 0)
                } else {
                    ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT)
                    ProgressLayout.clearProgress(ProgressLayout.CHECKING_MODS)
                }
            }

            if (!networkAvailable) {
                // No network: login is impossible, but the player can still launch the game
				// (a temporary offline account with the same name is created).
                Toast.makeText(context, context.getString(R.string.account_login_no_network), Toast.LENGTH_SHORT).show()
                launch(true)
                return
            }

            val currentAccount = AccountsManager.currentAccount
            if (currentAccount == null || AccountUtils.isNoLoginRequired(currentAccount)) {
                launch()
                return
            }

            AccountsManager.performLogin(
                context, currentAccount,
                { _ ->
                    EventBus.getDefault().post(AccountUpdateEvent())
                    TaskExecutors.runInUIThread {
                        Toast.makeText(context, context.getString(R.string.account_login_done), Toast.LENGTH_SHORT).show()
                    }
                    // Login done, launch the game for real!
                    launch()
                },
                { exception ->
                    val errorMessage = if (exception is PresentedException) exception.toString(context)
                    else exception.message

                    TaskExecutors.runInUIThread {
                        TipDialog.Builder(context)
                            .setTitle(R.string.generic_error)
                            .setMessage("${context.getString(R.string.account_login_skip)}\r\n$errorMessage")
                            .setWarning()
                            .setConfirmClickListener { launch(true) }
                            .setCenterMessage(false)
                            .showDialog()
                    }

                    setGameProgress(false)
                }
            )
            setGameProgress(true)
        }

        @Throws(Throwable::class)
        @JvmStatic
        fun runGame(activity: AppCompatActivity, minecraftVersion: Version, version: JMinecraftVersionList.Version) {
            // Auto Settings Optimizer: pick best renderer+driver+RAM+FPS Boost flags for this device
            if (AllSettings.autoSettingsOptimizer.getValue()) {
                AutoSettingsOptimizer.apply(activity, version.id ?: "")
            }

            if (!Renderers.isCurrentRendererValid()) {
                Renderers.setCurrentRenderer(activity, AllSettings.renderer.getValue())
            }

                var account = AccountsManager.currentAccount
                if (account == null) {
                    Logging.w("LaunchGame", "No account set, launching with a throwaway offline account")
                    account = MinecraftAccount().apply {
                        this.username = "Player"
                        this.accountType = AccountType.LOCAL.type
                        this.profileId = MinecraftAccount.generateOfflineUUID("Player").toString()
                    }
                }
                if (minecraftVersion.offlineAccountLogin) {
                val offlineUsername = account.username
                account = MinecraftAccount().apply {
                    this.username = offlineUsername
                    this.accountType = AccountType.LOCAL.type
                    this.profileId = MinecraftAccount.generateOfflineUUID(offlineUsername).toString()
                }
            }

            val baseArgs = minecraftVersion.getJavaArgs().takeIf { it.isNotBlank() } ?: ""

            // Resolve actual required Java version — Mojang's JSON still reports 21
            // for MC 26.x even though 26.1.2+ requires Java 25. Override here.
            val jsonJavaVersion = version.javaVersion?.majorVersion ?: 8
            val mcId = version.id ?: ""
            val requiredJava = LaunchArgs.resolveRequiredJava(mcId, jsonJavaVersion)
            val javaRuntime = getRuntime(activity, minecraftVersion, requiredJava)

            val actualJavaVersion = runCatching { MultiRTUtils.read(javaRuntime).javaVersion }
                .getOrDefault(requiredJava)
                .let { if (it <= 0) requiredJava else it }
            val customArgs = (baseArgs + " " + buildFpsBoostArgs(actualJavaVersion)).trim()

            printLauncherInfo(
                minecraftVersion,
                customArgs.takeIf { it.isNotBlank() } ?: "NONE",
                javaRuntime,
                account
            )

            minecraftVersion.modCheckResult?.let { modCheckResult ->
                if (modCheckResult.hasTouchController) {
                    Logger.appendToLog("Mod Perception: TouchController Mod found, attempting to automatically enable control proxy!")
                    ControllerProxy.startProxy(activity)
                    AllStaticSettings.useControllerProxy = true
                }

                if (modCheckResult.hasSodiumOrEmbeddium) {
                    Logger.appendToLog("Mod Perception: Sodium or Embeddium Mod found, attempting to load the disable warning tool later!")
                }
            }

            if (!AllSettings.fastBoot.getValue()) {
                JREUtils.redirectAndPrintJRELog()
            }

            launch(activity, account, minecraftVersion, javaRuntime, customArgs)

            //Note that we actually stall in the above function, even if the game crashes. But let's be safe.
            GameService.setActive(false)
        }

        private fun getRuntime(activity: Activity, version: Version, targetJavaVersion: Int): String {
            val versionRuntime = version.getJavaDir()
                .takeIf { it.isNotEmpty() && it.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX) }
                ?.removePrefix(Tools.LAUNCHERPROFILES_RTPREFIX)
                ?: ""

            if (versionRuntime.isNotEmpty()) return versionRuntime

            var autoInstalled = TurtleJREAutoInstaller.ensureJavaInstalled(activity, targetJavaVersion)
            if (autoInstalled != null) {
                val rt = MultiRTUtils.read(autoInstalled)
                if (rt.javaVersion >= targetJavaVersion) {
                    Logging.i("LaunchGame", "Auto-installed Java runtime: $autoInstalled")
                    return autoInstalled
                }
                Logging.e("LaunchGame", "Auto-installed runtime '$autoInstalled' reports javaVersion=${rt.javaVersion} but $targetJavaVersion was required - retrying auto-install once")
                autoInstalled = TurtleJREAutoInstaller.ensureJavaInstalled(activity, targetJavaVersion)
                if (autoInstalled != null) {
                    val retryRt = MultiRTUtils.read(autoInstalled)
                    if (retryRt.javaVersion >= targetJavaVersion) {
                        Logging.i("LaunchGame", "Auto-installed Java runtime on retry: $autoInstalled")
                        return autoInstalled
                    }
                }
            } else {
                Logging.e("LaunchGame", "TurtleJREAutoInstaller could not provide Java $targetJavaVersion (network unreachable, or download/verification failed) - falling back to whatever runtime is already installed")
            }

            // If the version has no Java runtime set, pick a suitable one automatically.
            var runtime = AllSettings.defaultRuntime.getValue()
            val pickedRuntime = MultiRTUtils.read(runtime)
            if (pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
                runtime = MultiRTUtils.getNearestJreName(targetJavaVersion) ?: run {
                    Logging.e("LaunchGame", "No installed runtime satisfies Java $targetJavaVersion (installed: '$runtime' is Java ${pickedRuntime.javaVersion}) and auto-install failed - launch will very likely crash with UnsupportedClassVersionError")
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.game_autopick_runtime_failed) + " (" + activity.getString(R.string.game_requires_java_version, targetJavaVersion) + ")",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return runtime
                }
            }
            return runtime
        }

        private fun printLauncherInfo(
            minecraftVersion: Version,
            javaArguments: String,
            javaRuntime: String,
            account: MinecraftAccount
        ) {
            var mcInfo = minecraftVersion.getVersionName()
            minecraftVersion.getVersionInfo()?.let { info ->
                mcInfo = info.getInfoString()
            }

            if (AllSettings.fastBoot.getValue()) {
                // Fast Boot: trim to the essentials only, skip the verbose multi-line dump.
                Logger.appendToLog("Fast Boot: launching ${minecraftVersion.getVersionName()} ($mcInfo) | Account: ${account.username}")
                return
            }

            Logger.appendToLog("--------- Start launching the game")
            Logger.appendToLog("Info: Launcher version: ${ZHTools.getVersionName()} (${ZHTools.getVersionCode()})")
            Logger.appendToLog("Info: Architecture: ${Architecture.archAsString(Tools.DEVICE_ARCHITECTURE)}")
            Logger.appendToLog("Info: Device model: ${StringUtils.insertSpace(Build.MANUFACTURER, Build.MODEL)}")
            Logger.appendToLog("Info: API version: ${Build.VERSION.SDK_INT}")
            Logger.appendToLog("Info: Renderer: ${Renderers.getCurrentRenderer().getRendererName()}")
            Logger.appendToLog("Info: Selected Minecraft version: ${minecraftVersion.getVersionName()}")
            Logger.appendToLog("Info: Minecraft Info: $mcInfo")
            Logger.appendToLog("Info: Game Path: ${minecraftVersion.getGameDir().absolutePath} (Isolation: ${minecraftVersion.isIsolation()})")
            Logger.appendToLog("Info: Custom Java arguments: $javaArguments")
            Logger.appendToLog("Info: Java Runtime: $javaRuntime")
            Logger.appendToLog("Info: Account: ${account.username} (${account.accountType})")
            Logger.appendToLog("---------\r\n")
        }

        @Throws(Throwable::class)
        @JvmStatic
        private fun launch(
            activity: AppCompatActivity,
            account: MinecraftAccount,
            minecraftVersion: Version,
            javaRuntime: String,
            customArgs: String
        ) {
            androidx.tracing.Trace.beginSection("LaunchGame.launch")
            try {
                // Fast Boot: skip the pre-launch RAM check dialog (saves a blocking UI step)
                if (!AllSettings.fastBoot.getValue()) {
                    checkMemory(activity)
                }

                // Fast Boot: skip the forced cache-bust and use whatever's already cached
                // (the runtime's release file rarely changes between launches).
                androidx.tracing.Trace.beginSection("LaunchGame.resolveRuntime")
                val runtime = if (AllSettings.fastBoot.getValue())
                    MultiRTUtils.read(javaRuntime)
                else
                    MultiRTUtils.forceReread(javaRuntime)
                androidx.tracing.Trace.endSection()

                val versionInfo = Tools.getVersionInfo(minecraftVersion)
                val gameDirPath = minecraftVersion.getGameDir()

                // Pre-processing.
                Tools.disableSplash(gameDirPath)
                androidx.tracing.Trace.beginSection("LaunchGame.classpath")
                val launchClassPath = Tools.generateLaunchClassPath(versionInfo, minecraftVersion)
                androidx.tracing.Trace.endSection()

                val launchArgs = LaunchArgs(
                    account,
                    gameDirPath,
                    minecraftVersion,
                    versionInfo,
                    minecraftVersion.getVersionName(),
                    runtime,
                    launchClassPath
                ).getAllArgs()

                FFmpegPlugin.discover(activity)

                androidx.tracing.Trace.beginSection("LaunchGame.launchWithUtils")
                JREUtils.launchWithUtils(activity, runtime, minecraftVersion, launchArgs, customArgs)
                androidx.tracing.Trace.endSection()
            } finally {
                androidx.tracing.Trace.endSection()
            }
        }

        /**
         * Builds extra JVM args from TurtleLauncher's FPS Boost toggles.
         * Appended to the per-version custom args string before launch.
         */
        private fun buildFpsBoostArgs(javaMajorVersion: Int): String {
            val args = mutableListOf<String>()

            args += "-XX:+UseG1GC"
            args += "-XX:MaxGCPauseMillis=50"

            if (AllSettings.unlimitedFps.getValue()) {
                // Prevents mod/library System.gc() calls from forcing full GC pauses.
                args += "-XX:+DisableExplicitGC"
            }

            if (AllSettings.lowLatencyRendering.getValue()) {
                args += "-XX:+UseG1GC"
                args += "-XX:+UseStringDeduplication"
                // Skips attaching the JVM's perfdata file to shared memory - removes a real
                // source of I/O-related micro-jitter, safe on every Java version this
                // launcher supports.
                args += "-XX:+PerfDisableSharedMem"
            }

            if (AllSettings.framePacing.getValue()) {
                args += "-XX:+AlwaysPreTouch"
            }

            if (AllSettings.adaptiveFrameTiming.getValue()) {
                args += "-XX:MaxGCPauseMillis=10"
            }

            if (AllSettings.autoMemoryCleanup.getValue()) {
                if (javaMajorVersion >= 12) {
                    args += "-XX:+UnlockExperimentalVMOptions"
                    args += "-XX:G1PeriodicGCInterval=300000"
                } else {
                    Logging.w("LaunchGame", "autoMemoryCleanup wants G1PeriodicGCInterval, which needs Java 12+ " +
                        "(this instance is resolved to Java $javaMajorVersion) - skipping it rather than passing an " +
                        "invalid VM option that would prevent the JVM from starting")
                }
            }

            return args.joinToString(" ")
        }

        private fun checkMemory(activity: AppCompatActivity) {
            var freeDeviceMemory = Tools.getFreeDeviceMemory(activity)
            val freeAddressSpace =
                if (Architecture.is32BitsDevice())
                    Tools.getMaxContinuousAddressSpaceSize()
                else -1
            Logging.i("MemStat",
                "Free RAM: $freeDeviceMemory Addressable: $freeAddressSpace")

            val stringId: Int = if (freeDeviceMemory > freeAddressSpace && freeAddressSpace != -1) {
                freeDeviceMemory = freeAddressSpace
                R.string.address_memory_warning_msg
            } else R.string.memory_warning_msg

            if (AllSettings.ramAllocation.value.getValue() > freeDeviceMemory) {
                val builder = TipDialog.Builder(activity)
                    .setTitle(R.string.generic_warning)
                    .setMessage(activity.getString(stringId, freeDeviceMemory, AllSettings.ramAllocation.value.getValue()))
                    .setWarning()
                    .setCenterMessage(false)
                    .setShowCancel(false)
                if (LifecycleAwareTipDialog.haltOnDialog(activity.lifecycle, builder)) return
                // If the dialog's lifecycle has ended, return without
                // actually launching the game, thus giving us the opportunity
                // to start after the activity is shown again
            }
        }
    }
}