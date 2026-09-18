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
import net.endiq.launcher.Tools
import com.endiq.turtlelauncher.feature.mod.modloader.CleanroomDownloadTask
import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.ui.fragment.InstallGameFragment.Companion.BUNDLE_MC_VERSION
import net.endiq.launcher.modloaders.CleanroomUtils
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.Future

/**
 * Cleanroom only ever targets Minecraft 1.12.2 (see CleanroomUtils for the source of
 * that fact) - for every other version this screen shows a specific "Incompatible
 * with <version>" message instead of the generic "no versions available" one, per
 * Endiq's explicit request, rather than looking like a loading/network failure.
 */
class DownloadCleanroomFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadCleanroomFragment"
    }

    override fun refreshCreatedView() {
        fragmentActivity?.let { setIcon(ContextCompat.getDrawable(it, R.drawable.ic_anvil)) }
        setTitleText("Cleanroom")
        setLink("https://cleanroommc.com/")
        setMCMod("https://github.com/CleanroomMC/Cleanroom")
        setReleaseCheckBoxGone()
    }

    override fun initRefresh(): Future<*> = refresh(false)
    override fun refresh(): Future<*> = refresh(true)

    private fun refresh(force: Boolean): Future<*> {
        return TaskExecutors.getDefault().submit {
            runCatching {
                TaskExecutors.runInUIThread {
                    cancelFailedToLoad()
                    componentProcessing(true)
                }

                val mcVersion = arguments?.getString(BUNDLE_MC_VERSION)
                    ?: throw IllegalArgumentException("The Minecraft version is not passed")

                if (!CleanroomUtils.isCompatible(mcVersion)) {
                    TaskExecutors.runInUIThread {
                        componentProcessing(false)
                        setFailedToLoad(getString(R.string.version_install_incompatible_mc_version, mcVersion))
                    }
                    return@runCatching
                }

                val cleanroomVersions = CleanroomUtils.downloadCleanroomVersions(mcVersion, force)
                processVersions(cleanroomVersions)
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadCleanroom", Tools.printToString(e))
            }
        }
    }

    private fun processVersions(cleanroomVersions: List<String>) {
        if (cleanroomVersions.isEmpty()) {
            TaskExecutors.runInUIThread {
                componentProcessing(false)
                setFailedToLoad(getString(R.string.version_install_no_versions))
            }
            return
        }

        currentTask?.apply { if (isCancelled) return }

        val adapter = ModVersionListAdapter(R.drawable.ic_anvil, cleanroomVersions)
        adapter.setOnItemClickListener { version: Any ->
            if (isTaskRunning()) return@setOnItemClickListener false

            val versionString = version.toString()
            EventBus.getDefault().postSticky(
                SelectInstallTaskEvent(
                    Addon.CLEANROOM,
                    versionString,
                    CleanroomDownloadTask(versionString)
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
