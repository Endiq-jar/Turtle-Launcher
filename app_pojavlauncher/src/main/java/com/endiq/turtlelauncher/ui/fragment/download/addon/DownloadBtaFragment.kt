package com.endiq.turtlelauncher.ui.fragment.download.addon

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.event.sticky.SelectInstallTaskEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.modloader.BtaInstallTask
import com.endiq.turtlelauncher.feature.mod.modloader.ModVersionListAdapter
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListFragment
import com.endiq.turtlelauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import com.endiq.turtlelauncher.feature.version.install.Addon
import net.kdt.pojavlaunch.modloaders.BTAUtils
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.Future
import java.util.function.Consumer

/**
 * BTA (Better Than Adventure!) version picker — Amethyst-stack loader surfaced in
 * the Turtle download center. Picking a version queues the self-contained BTA
 * installer as an install-flow task.
 */
class DownloadBtaFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadBtaFragment"
    }

    override fun refreshCreatedView() {
        fragmentActivity?.let { setIcon(ContextCompat.getDrawable(it, R.drawable.ic_old_grass_block)) }
        setTitleText("BTA")
        setLink("https://www.betterthanadventure.com/")
        setMCMod("https://www.mcmod.cn/class/4717.html")
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
                processVersions(BTAUtils.downloadVersionList())
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadBta", Tools.printToString(e))
            }
        }
    }

    private fun empty() {
        TaskExecutors.runInUIThread {
            componentProcessing(false)
            setFailedToLoad(getString(R.string.version_install_no_versions))
        }
    }

    private fun processVersions(versionList: BTAUtils.BTAVersionList?) {
        versionList ?: run { empty(); return }

        // Tested releases first, then nightlies (marked), skipping untested duplicates.
        val versions = mutableListOf<BTAUtils.BTAVersion>()
        versionList.testedVersions?.let { versions.addAll(it) }
        versionList.nightlyVersions?.forEach(Consumer { v ->
            if (versions.none { it.versionName == v.versionName }) versions.add(v)
        })
        if (versions.isEmpty()) run { empty(); return }

        currentTask?.apply { if (isCancelled) return }

        val versionNames = versions.map { v ->
            if (versionList.nightlyVersions?.any { it.versionName == v.versionName } == true &&
                versionList.testedVersions?.none { it.versionName == v.versionName } == true
            ) "${v.versionName} (nightly)" else v.versionName
        }

        val adapter = ModVersionListAdapter(R.drawable.ic_old_grass_block, versionNames)
        adapter.setOnItemClickListener { version: Any ->
            if (isTaskRunning()) return@setOnItemClickListener false

            val display = version.toString()
            val nightly = display.endsWith(" (nightly)")
            val rawName = if (nightly) display.removeSuffix(" (nightly)") else display
            val selected = versions.firstOrNull { it.versionName == rawName }
                ?: return@setOnItemClickListener false

            EventBus.getDefault().postSticky(
                SelectInstallTaskEvent(Addon.BTA, rawName, BtaInstallTask(selected))
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
