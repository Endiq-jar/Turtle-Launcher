package com.endiq.turtlelauncher.ui.fragment.download.addon

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.event.sticky.SelectInstallTaskEvent
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.modloader.ModVersionListAdapter
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListFragment
import com.endiq.turtlelauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import com.endiq.turtlelauncher.feature.mod.modloader.ForgeDownloadTask
import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.ui.fragment.InstallGameFragment.Companion.BUNDLE_MC_VERSION
import net.kdt.pojavlaunch.modloaders.ForgeUtils
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.Future
import java.util.function.Consumer

class DownloadForgeFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadForgeFragment"
    }

    override fun refreshCreatedView() {
        fragmentActivity?.let { setIcon(ContextCompat.getDrawable(it, R.drawable.ic_anvil)) }
        setTitleText("Forge")
        setLink("https://forums.minecraftforge.net/")
        setMCMod("https://www.mcmod.cn/class/30.html")
        setReleaseCheckBoxGone() //隐藏“仅展示正式版”选择框，在这里没有用处
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
                val forgeVersions = ForgeUtils.downloadForgeVersions(force)
                processModDetails(forgeVersions)
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadForge", Tools.printToString(e))
            }
        }
    }

    private fun empty() {
        TaskExecutors.runInUIThread {
            componentProcessing(false)
            setFailedToLoad(getString(R.string.version_install_no_versions))
        }
    }

    private fun processModDetails(forgeVersions: List<String>?) {
        forgeVersions ?: run {
            empty()
            return
        }

        val mcVersion = arguments?.getString(BUNDLE_MC_VERSION) ?: throw IllegalArgumentException("The Minecraft version is not passed")

        val mForgeVersions: MutableMap<String, MutableList<String>> = HashMap()
        forgeVersions.forEach(Consumer { forgeVersion: String ->
            currentTask?.apply { if (isCancelled) return@Consumer }

            //查找并分组Minecraft版本与Forge版本 (a version string without a dash
            // would make substring(0, -1) crash - skip the malformed entry)
            val dashIndex = forgeVersion.indexOf("-")
            if (dashIndex <= 0) return@Consumer
            val gameVersion = forgeVersion.substring(0, dashIndex)
            addIfAbsent(mForgeVersions, gameVersion, forgeVersion)
        })

        currentTask?.apply { if (isCancelled) return }

        val mcForgeVersions = mForgeVersions[mcVersion] ?: run {
            empty()
            return
        }

        val adapter = ModVersionListAdapter(R.drawable.ic_anvil, mcForgeVersions)
        adapter.setOnItemClickListener { version: Any ->
            if (isTaskRunning()) return@setOnItemClickListener false

            val versionString = version.toString()
            EventBus.getDefault().postSticky(
                SelectInstallTaskEvent(
                    Addon.FORGE,
                    versionString,
                    ForgeDownloadTask(versionString)
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
