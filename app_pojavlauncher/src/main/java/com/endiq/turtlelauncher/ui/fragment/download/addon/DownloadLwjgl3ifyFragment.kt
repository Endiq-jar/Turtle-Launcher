package com.endiq.turtlelauncher.ui.fragment.download.addon

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.event.sticky.SelectInstallTaskEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.modloader.Lwjgl3ifyInstallTask
import com.endiq.turtlelauncher.feature.mod.modloader.ModVersionListAdapter
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListFragment
import com.endiq.turtlelauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import com.endiq.turtlelauncher.feature.version.install.Addon
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.Future

/**
 * LWJGL3ify version picker — Amethyst-stack loader surfaced in the Turtle download
 * center. LWJGL3ify runs old (1.7.x era) modded Minecraft on modern Java runtimes;
 * picking a version queues its self-contained installer.
 */
class DownloadLwjgl3ifyFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadLwjgl3ifyFragment"
    }

    override fun refreshCreatedView() {
        fragmentActivity?.let { setIcon(ContextCompat.getDrawable(it, R.drawable.ic_java)) }
        setTitleText("LWJGL3ify")
        setLink("https://github.com/GTNewHorizons/lwjgl3ify")
        setReleaseCheckBoxGone()
    }

    override fun initRefresh(): Future<*> = refresh()

    override fun refresh(): Future<*> {
        return TaskExecutors.getDefault().submit {
            runCatching {
                TaskExecutors.runInUIThread {
                    cancelFailedToLoad()
                    componentProcessing(true)
                }
                val modpackApi = CommonApi(getString(R.string.curseforge_api_key))
                processVersions(LWJGL3ifyUtils.getLWJGL3ifyVersionList(modpackApi))
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadLwjgl3ify", Tools.printToString(e))
            }
        }
    }

    private fun empty() {
        TaskExecutors.runInUIThread {
            componentProcessing(false)
            setFailedToLoad(getString(R.string.version_install_no_versions))
        }
    }

    private fun processVersions(versionList: LWJGL3ifyUtils.LWJGL3ifyVersionList?) {
        versionList ?: run { empty(); return }

        val supported = versionList.supportedVersions ?: emptyList()
        val broken = versionList.brokenVersions ?: emptyList()
        if (supported.isEmpty() && broken.isEmpty()) run { empty(); return }

        currentTask?.apply { if (isCancelled) return }

        val displayNames = supported.map { it.versionName } +
                broken.map { "${it.versionName} (incompatible with SDL)" }

        val adapter = ModVersionListAdapter(R.drawable.ic_java, displayNames)
        adapter.setOnItemClickListener { version: Any ->
            if (isTaskRunning()) return@setOnItemClickListener false

            val display = version.toString()
            val isBroken = display.endsWith(" (incompatible with SDL)")
            val rawName = if (isBroken) display.removeSuffix(" (incompatible with SDL)") else display
            val selected = (supported + broken).firstOrNull { it.versionName == rawName }
                ?: return@setOnItemClickListener false

            EventBus.getDefault().postSticky(
                SelectInstallTaskEvent(
                    Addon.LWJGL3IFY,
                    rawName,
                    Lwjgl3ifyInstallTask(requireActivity(), selected)
                )
            )
            ZHTools.onBackPressed(requireActivity())
            true
        }

        TaskExecutors.runInUIThread {
            val recyclerView = recyclerView
            runCatching {
                fragmentActivity?.let { recyclerView.layoutManager = LinearLayoutManager(it) }
                recyclerView.adapter = adapter
            }.getOrElse { e ->
                Logging.e("Set Adapter", Tools.printToString(e))
            }
            componentProcessing(false)
            recyclerView.scheduleLayoutAnimation()
        }
    }
}
