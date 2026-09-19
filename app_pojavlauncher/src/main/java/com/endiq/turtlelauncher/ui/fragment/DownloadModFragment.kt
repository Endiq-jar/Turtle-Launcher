package com.endiq.turtlelauncher.ui.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import net.kdt.pojavlaunch.R
import com.endiq.turtlelauncher.event.value.DownloadPageEvent
import com.endiq.turtlelauncher.feature.download.InfoViewModel
import com.endiq.turtlelauncher.feature.download.ScreenshotAdapter
import com.endiq.turtlelauncher.feature.download.VersionAdapter
import com.endiq.turtlelauncher.feature.download.enums.Classify
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.item.InfoItem
import com.endiq.turtlelauncher.feature.download.item.ModVersionItem
import com.endiq.turtlelauncher.feature.download.item.ScreenshotItem
import com.endiq.turtlelauncher.feature.download.item.VersionItem
import com.endiq.turtlelauncher.feature.download.platform.AbstractPlatformHelper
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListAdapter
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListFragment
import com.endiq.turtlelauncher.ui.subassembly.modlist.ModListItemBean
import com.endiq.turtlelauncher.ui.view.AnimButton
import com.endiq.turtlelauncher.utils.MCVersionRegex.Companion.RELEASE_REGEX
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Tools
import org.greenrobot.eventbus.EventBus
import org.jackhuang.hmcl.ui.versions.ModTranslations
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.util.Objects
import java.util.concurrent.Future
import java.util.function.Consumer

class DownloadModFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadModFragment"
    }

    private lateinit var platformHelper: AbstractPlatformHelper
    private lateinit var mInfoItem: InfoItem
    private var linkGetSubmit: Future<*>? = null

    override fun init() {
        parseViewModel()
        super.init()
    }

    @SuppressLint("CheckResult")
    override fun refreshCreatedView() {
        linkGetSubmit = TaskExecutors.getDefault().submit {
            runCatching {
                val webUrl = platformHelper.getWebUrl(mInfoItem)
                fragmentActivity?.runOnUiThread { setLink(webUrl) }
            }.getOrElse { e ->
                Logging.e("DownloadModFragment", "Failed to retrieve the website link, ${Tools.printToString(e)}")
            }
        }

        mInfoItem.apply {
            val type = ModTranslations.getTranslationsByRepositoryType(classify)
            val mod = type.getModByCurseForgeId(slug)

            setTitleText(
                if (ZHTools.areaChecks("zh")) {
                    mod?.displayName ?: title
                } else title
            )
            setDescription(description)
            mod?.let {
                setMCMod(
                    StringUtilsKt.getNonEmptyOrBlank(type.getMcmodUrl(it))
                )
            }
            loadScreenshots()

            iconUrl?.let { url ->
                fragmentActivity?.let { activity ->
                    Glide.with(activity).load(url).apply {
                        if (!AllSettings.resourceImageCache.getValue()) diskCacheStrategy(DiskCacheStrategy.NONE)
                    }.into(getIconView())
                }
            }
        }
    }

    override fun initRefresh(): Future<*> {
        return refresh(false)
    }

    override fun refresh(): Future<*> {
        return refresh(true)
    }

    override fun onDestroy() {
        EventBus.getDefault().post(DownloadPageEvent.RecyclerEnableEvent(true))
        linkGetSubmit?.apply {
            if (!isCancelled && !isDone) cancel(true)
        }
        super.onDestroy()
    }

    private fun refresh(force: Boolean): Future<*> {
        return TaskExecutors.getDefault().submit {
            runCatching {
                TaskExecutors.runInUIThread {
                    cancelFailedToLoad()
                    componentProcessing(true)
                }
                val versions = platformHelper.getVersions(mInfoItem, force)
                processDetails(versions)
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadModFragment", Tools.printToString(e))
            }
        }
    }

    private fun processDetails(versions: List<VersionItem>?) {
        val pattern = RELEASE_REGEX

        val releaseCheckBoxChecked = releaseCheckBox.isChecked
        // The key records both the MC version and the mod loader so loaders can be split later.
        val mModVersionsByMinecraftVersion: MutableMap<Pair<String, ModLoader?>, MutableList<VersionItem>> = HashMap()

        versions?.forEach(Consumer { versionItem ->
            currentTask?.apply { if (isCancelled) return@Consumer }

            for (mcVersion in versionItem.mcVersions) {
                currentTask?.apply { if (isCancelled) return@Consumer }

                if (releaseCheckBoxChecked) {
                    val matcher = pattern.matcher(mcVersion)
                    if (!matcher.matches()) {
                        // Not a release version: keep checking the next entry.
                        continue
                    }
                }

                if (versionItem is ModVersionItem) {
                    val modloaders = versionItem.modloaders
                    if (modloaders.isNotEmpty()) {
                        modloaders.forEach {
                            addIfAbsent(mModVersionsByMinecraftVersion, Pair(mcVersion, it), versionItem)
                        }
                        // For a ModVersionItem with a non-empty mod loader, bucket the loaders the
						// version supports into their own mod-loader lists.
                        // This makes it easier for users to find versions with the loader they need.
                        continue // already categorized, no need for the plain version list
                    }
                }
                addIfAbsent(mModVersionsByMinecraftVersion, Pair(mcVersion, null), versionItem)
            }
        })

        currentTask?.apply { if (isCancelled) return }

        val currentVersion = VersionsManager.getCurrentVersion()
        // Locate the first compatible version and remember its index; once loading finishes,
        // the RecyclerView scrolls to it.
        var firstAdaptIndex: Int? = null

        val mData: MutableList<ModListItemBean> = ArrayList()
        mModVersionsByMinecraftVersion.entries
            .sortedWith { entry1, entry2 ->
                val mcVersionComparison = -VersionNumber.compare(entry1.key.first, entry2.key.first)
                if (mcVersionComparison != 0) {
                    mcVersionComparison
                } else {
                    val name1 = entry1.key.second?.name ?: ""
                    val name2 = entry2.key.second?.name ?: ""
                    // Keep versions with a ModLoader first.
                    if (name1.isEmpty() && name2.isNotEmpty()) 1
                    else if (name1.isNotEmpty() && name2.isEmpty()) -1
                    else name1.compareTo(name2)
                }
            }
            .forEachIndexed { index: Int, entry: Map.Entry<Pair<String, ModLoader?>, List<VersionItem>> ->
                currentTask?.apply { if (isCancelled) return }

                val isAdapt: Boolean = when (mInfoItem.classify) {
                    Classify.MODPACK -> false
                    else -> currentVersion?.let { version ->
                        val itemVersion = VersionNumber.asVersion(entry.key.first).canonical
                        val currentVersionString = VersionNumber.asVersion(version.getVersionInfo()?.minecraftVersion ?: "").canonical

                        if (!Objects.equals(itemVersion, currentVersionString)) return@let false

                        val modloader = entry.key.second
                        val loaderInfo = version.getVersionInfo()?.loaderInfo

                        when {
                            // The resource carries no mod-loader info, so treat it as compatible.
                            modloader == null -> true
                            // The resource needs a mod loader but this version has none: not compatible.
                            // (What mods did you expect to install without a mod loader?)
                            loaderInfo == null -> false
                            // Match the mod loader.
                            else -> loaderInfo.any { loader -> Objects.equals(modloader.loaderName, loader.name) }
                        }
                    } ?: false
                }

                if (isAdapt) {
                    firstAdaptIndex ?: run {
                        firstAdaptIndex = index
                    }
                }

                mData.add(
                    ModListItemBean(
                        entry.key.first,
                        entry.key.second,
                        isAdapt,
                        VersionAdapter(mInfoItem, platformHelper, entry.value)
                    )
                )
            }

        currentTask?.apply { if (isCancelled) return }

        Task.runTask(TaskExecutors.getAndroidUI()) {
            runCatching {
                var modAdapter = recyclerView.adapter as ModListAdapter?
                modAdapter ?: run {
                    modAdapter = ModListAdapter(this, mData)
                    fragmentActivity?.let { recyclerView.layoutManager = LinearLayoutManager(it) }
                    recyclerView.adapter = modAdapter
                    return@runCatching
                }
                modAdapter?.updateData(mData)
            }.getOrElse { e ->
                Logging.e("Set Adapter", Tools.printToString(e))
            }

            componentProcessing(false)
            recyclerView.scheduleLayoutAnimation()

            firstAdaptIndex?.let {
                recyclerView.postDelayed(
                    {
                        // Scroll straight to the "first compatible" index found earlier, offset by two.
                        recyclerView.smoothScrollToPosition((it + 2).coerceAtMost(mData.size - 1))
                    },
                    500
                )
            }
        }.execute()
    }

    private fun parseViewModel() {
        val activity = fragmentActivity ?: return
        val viewModel = ViewModelProvider(activity)[InfoViewModel::class.java]
        platformHelper = viewModel.platformHelper ?: run {
            ZHTools.onBackPressed(activity)
            return
        }
        mInfoItem = viewModel.infoItem ?: run {
            ZHTools.onBackPressed(activity)
            return
        }
    }

    private fun loadScreenshots() {
        val activity = fragmentActivity ?: return
        val progressBar = createProgressView(activity)
        addMoreView(progressBar)

        Task.runTask {
            platformHelper.getScreenshots(mInfoItem.projectId)
        }.ended(TaskExecutors.getAndroidUI()) { screenshotItems ->
            screenshotItems?.let addButton@{ items ->
                if (items.isEmpty()) return@addButton
                fragmentActivity?.let { activity ->
                    // Add a button that loads the screenshot data when tapped.
                    addMoreView(AnimButton(activity).apply {
                        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        setText(R.string.download_info_load_screenshot)
                        setOnClickListener {
                            setScreenshotView(items)
                            AnimPlayer.play().apply(AnimPlayer.Entry(this, Animations.FadeOut))
                                .setOnEnd { removeMoreView(this) }
                                .start()
                        }
                    })
                }
            }
            removeMoreView(progressBar)
        }.onThrowable { e ->
            Logging.e(
                "DownloadModFragment",
                "Unable to load screenshots, ${Tools.printToString(e)}"
            )
        }.execute()
    }

    @SuppressLint("CheckResult")
    private fun setScreenshotView(screenshotItems: List<ScreenshotItem>) {
        fragmentActivity?.let { activity ->
            val recyclerView = RecyclerView(activity).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                layoutManager = LinearLayoutManager(activity)
                adapter = ScreenshotAdapter(screenshotItems)
            }

            addMoreView(recyclerView)
        }
    }

    private fun createProgressView(context: Context): ProgressBar {
        return ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }
}
