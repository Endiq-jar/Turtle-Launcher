package com.endiq.turtlelauncher.ui.fragment.download.addon

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.event.sticky.SelectInstallTaskEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.modloader.ModVersionListAdapter
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListFragment
import com.endiq.turtlelauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import com.endiq.turtlelauncher.feature.mod.modloader.OptiFineDownloadTask
import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.ui.fragment.InstallGameFragment.Companion.BUNDLE_MC_VERSION
import net.kdt.pojavlaunch.modloaders.OptiFineUtils
import net.kdt.pojavlaunch.modloaders.OptiFineUtils.OptiFineVersion
import net.kdt.pojavlaunch.modloaders.OptiFineUtils.OptiFineVersions
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.Future
import java.util.function.Consumer

class DownloadOptiFineFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadOptiFineFragment"
    }

    override fun refreshCreatedView() {
        fragmentActivity?.let { setIcon(ContextCompat.getDrawable(it, R.drawable.ic_optifine)) }
        setTitleText("OptiFine")
        setLink("https://www.optifine.net/home")
        setMCMod("https://www.mcmod.cn/class/36.html")
        setReleaseCheckBoxGone()
    }

    override fun initRefresh(): Future<*> {
        return refresh(false)
    }

    override fun refresh(): Future<*> {
        return refresh(true)
    }

    private fun refresh(force: Boolean): Future<*> {
        return TaskExecutors.getDefault().submit {
            runCatching {
                TaskExecutors.runInUIThread {
                    cancelFailedToLoad()
                    componentProcessing(true)
                }
                val optiFineVersions = OptiFineUtils.downloadOptiFineVersions(force)
                processModDetails(optiFineVersions)
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadOptiFineFragment", Tools.printToString(e))
            }
        }
    }

    private fun empty() {
        TaskExecutors.runInUIThread {
            componentProcessing(false)
            setFailedToLoad(getString(R.string.version_install_no_versions))
        }
    }

    private fun processModDetails(optiFineVersions: OptiFineVersions?) {
        optiFineVersions ?: run {
            empty()
            return
        }

        val mcVersion = arguments?.getString(BUNDLE_MC_VERSION) ?: throw IllegalArgumentException("The Minecraft version is not passed")

        val mOptiFineVersions: MutableMap<String, MutableList<OptiFineVersion>> = HashMap()
        optiFineVersions.optifineVersions.forEach(Consumer<List<OptiFineVersion>> { optiFineVersionList: List<OptiFineVersion> ->  // flatten the version lists into a Minecraft+OptiFine version map
            currentTask?.apply { if (isCancelled) return@Consumer }

            optiFineVersionList.forEach(Consumer Consumer2@{ optiFineVersion: OptiFineVersion ->
                currentTask?.apply { if (isCancelled) return@Consumer2 }
                addIfAbsent(mOptiFineVersions, optiFineVersion.minecraftVersion.removePrefix("Minecraft").trim(), optiFineVersion)
            })
        })

        if (currentTask?.isCancelled == true) return

        val mcOptiFineVersions = mOptiFineVersions[mcVersion] ?: mOptiFineVersions[mcVersion] ?: run {
            empty()
            return
        }

        val adapter = ModVersionListAdapter(R.drawable.ic_optifine, mcOptiFineVersions)
        adapter.setOnItemClickListener { version: Any ->
            if (isTaskRunning()) return@setOnItemClickListener false

            val optifineVersion = version as OptiFineVersion
            EventBus.getDefault().postSticky(
                SelectInstallTaskEvent(
                    Addon.OPTIFINE,
                    optifineVersion.versionName,
                    OptiFineDownloadTask(optifineVersion)
                )
            )
            ZHTools.onBackPressed(requireActivity())
            true
        }

        currentTask?.apply { if (isCancelled) return }

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
