package com.endiq.turtlelauncher.ui.fragment.download.resource

import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentDownloadResourceBinding
import com.endiq.turtlelauncher.event.value.DownloadPageEvent
import com.endiq.turtlelauncher.event.value.DownloadPageEvent.PageSwapEvent.Companion.IN
import com.endiq.turtlelauncher.event.value.DownloadPageEvent.PageSwapEvent.Companion.OUT
import com.endiq.turtlelauncher.feature.download.Filters
import com.endiq.turtlelauncher.feature.download.InfoAdapter
import com.endiq.turtlelauncher.feature.download.SelfReferencingFuture
import com.endiq.turtlelauncher.feature.download.enums.Category
import com.endiq.turtlelauncher.feature.download.enums.Classify
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.enums.Platform
import com.endiq.turtlelauncher.feature.download.enums.Sort
import com.endiq.turtlelauncher.feature.download.item.InfoItem
import com.endiq.turtlelauncher.feature.download.item.SearchResult
import com.endiq.turtlelauncher.feature.download.platform.PlatformNotSupportedException
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.SelectVersionDialog
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.ui.subassembly.adapter.ObjectSpinnerAdapter
import com.endiq.turtlelauncher.ui.subassembly.versionlist.VersionSelectedListener
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.skydoves.powerspinner.PowerSpinnerView
import net.endiq.launcher.Tools
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.util.concurrent.Future


abstract class AbstractResourceDownloadFragment(
    parentFragment: Fragment?,
    private val classify: Classify,
    private val categoryList: List<Category>,
    private val showModloader: Boolean,
    private val recommendedPlatform: Platform = Platform.MODRINTH
) : FragmentWithAnim(R.layout.fragment_download_resource) {
    private lateinit var binding: FragmentDownloadResourceBinding

    private lateinit var mPlatformAdapter: ObjectSpinnerAdapter<Platform>
    private lateinit var mSortAdapter: ObjectSpinnerAdapter<Sort>
    private lateinit var mCategoryAdapter: ObjectSpinnerAdapter<Category>
    private lateinit var mModLoaderAdapter: ObjectSpinnerAdapter<ModLoader>
    private var mCurrentPlatform: Platform = Platform.MODRINTH
    private val mFilters: Filters = Filters()

    private val mInfoAdapter = InfoAdapter(parentFragment,
        object : InfoAdapter.CallSearchListener {
            override fun isLastPage() = mLastPage

            override fun loadMoreResult() {
                mTaskInProgress?.let { return }
                mTaskInProgress = SelfReferencingFuture(SearchApiTask(mCurrentResult))
                    .startOnExecutor(TaskExecutors.getDefault())
            }
        })

    private var mTaskInProgress: Future<*>? = null
    private var mCurrentResult: SearchResult? = null
    protected var mLastPage = false

    abstract fun initInstallButton(installButton: Button)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDownloadResourceBinding.inflate(layoutInflater)

        mPlatformAdapter = ObjectSpinnerAdapter(binding.platformSpinner) { platform -> platform.pName }
        mSortAdapter = ObjectSpinnerAdapter(binding.sortSpinner) { sort -> getString(sort.resNameID) }
        mCategoryAdapter = ObjectSpinnerAdapter(binding.categorySpinner) { category -> getString(category.resNameID) }
        mModLoaderAdapter = ObjectSpinnerAdapter(binding.modloaderSpinner) { modloader ->
            if (modloader == ModLoader.ALL) getString(R.string.generic_all)
            else modloader.loaderName
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.apply {
            recyclerView.apply {
                layoutManager = LinearLayoutManager(requireContext())
                layoutAnimation = TurtleTransitions.listLayoutAnimationController(requireContext())
                // The list container size is fixed, so extra measure passes can be skipped.
                setHasFixedSize(true)
                // Search results are long and scrolled fast; a bigger view cache reduces jank.
                setItemViewCacheSize(16)
                addOnScrollListener(object : OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val lm = layoutManager as LinearLayoutManager
                        val lastPosition = lm.findLastVisibleItemPosition()
                        setVisibilityAnim(backToTop, lastPosition >= 12)
                    }
                })
                adapter = mInfoAdapter
            }

            backToTop.setOnClickListener { recyclerView.smoothScrollToPosition(0) }

            searchView.setOnClickListener { search() }
            nameEdit.doAfterTextChanged { text ->
                mFilters.name = text?.toString() ?: ""
            }
            nameEdit.setOnEditorActionListener { _, _, _ ->
                search()
                nameEdit.clearFocus()
                false
            }

            // Open the version picker dialog.
            selectedMcVersionView.setOnClickListener {
                val selectVersionDialog = SelectVersionDialog(requireContext())
                selectVersionDialog.setOnVersionSelectedListener(object : VersionSelectedListener() {
                    override fun onVersionSelected(version: String?) {
                        selectedMcVersionView.text = version
                        mFilters.mcVersion = version
                        selectVersionDialog.dismiss()
                    }
                })
                selectVersionDialog.show()
            }
            selectedMcVersionView.setOnLongClickListener {
                selectedMcVersionView.text = null
                true
            }
        }

        // Initialise the spinner.
        mPlatformAdapter.setItems(Platform.entries)
        mSortAdapter.setItems(Sort.entries)
        mCategoryAdapter.setItems(categoryList)
        mModLoaderAdapter.setItems(ModLoader.entries)

        binding.apply {
            initInstallButton(binding.installButton)

            setSpinner(platformSpinner, mPlatformAdapter)
            setSpinnerListener<Platform>(platformSpinner) {
                if (mCurrentPlatform == it) return@setSpinnerListener
                mCurrentPlatform = it
                search()
            }

            setSpinner(sortSpinner, mSortAdapter)
            setSpinnerListener<Sort>(sortSpinner) { mFilters.sort = it }

            setSpinner(categorySpinner, mCategoryAdapter)
            setSpinnerListener<Category>(binding.categorySpinner) { mFilters.category = it }

            modloaderLayout.visibility = if (showModloader) {
                setSpinner(modloaderSpinner, mModLoaderAdapter)
                setSpinnerListener<ModLoader>(modloaderSpinner) {
                    mFilters.modloader = it.takeIf { loader -> loader != ModLoader.ALL }
                }
                View.VISIBLE
            } else {
                mFilters.modloader = null
                View.GONE
            }

            initSpinnerIndex()

            reset.setOnClickListener {
                nameEdit.setText("")
                initSpinnerIndex()
                binding.selectedMcVersionView.text = null
                mFilters.mcVersion = null
            }

            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
        }

        initialSearchQuery()?.let { query ->
            binding.nameEdit.setText(query)
        }

        checkSearch()
    }

    /**
     * Override to pre-fill the search box when this fragment is opened with a specific
     * target already known (e.g. a featured modpack). Returning null (the default)
     * preserves the normal "start with an empty search" behaviour.
     */
    protected open fun initialSearchQuery(): String? = null

    private fun setSpinner(spinner: PowerSpinnerView, adapter: ObjectSpinnerAdapter<*>) {
        spinner.apply {
            setSpinnerAdapter(adapter)
            setIsFocusable(true)
            lifecycleOwner = this@AbstractResourceDownloadFragment
        }
    }

    private fun initSpinnerIndex() {
        binding.apply {
            platformSpinner.selectItemByIndex(recommendedPlatform.ordinal)
            sortSpinner.selectItemByIndex(0)
            categorySpinner.selectItemByIndex(0)
            if (showModloader) modloaderSpinner.selectItemByIndex(0)
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        closeSpinner()
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    private fun onSearchFinished() {
        binding.apply {
            setStatusText(false)
            setLoadingLayout(false)
            setRecyclerView(true)
        }
    }

    private fun onSearchError(error: Int) {
        binding.apply {
            statusText.text = when (error) {
                ERROR_INTERNAL -> getString(R.string.download_search_failed)
                ERROR_PLATFORM_NOT_SUPPORTED -> getString(R.string.download_search_platform_not_supported)
                else -> getString(R.string.download_search_no_result)
            }
        }
        setLoadingLayout(false)
        setRecyclerView(false)
        setStatusText(true)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.enter()))
                .apply(AnimPlayer.Entry(downloadLayout, TurtleTransitions.enter()))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.exit()))
                .apply(AnimPlayer.Entry(downloadLayout, TurtleTransitions.exit()))
        }
    }

    private fun setStatusText(shouldShow: Boolean) {
        setVisibilityAnim(binding.statusText, shouldShow)
    }

    private fun setLoadingLayout(shouldShow: Boolean) {
        setVisibilityAnim(binding.loadingLayout, shouldShow)
    }

    private fun setRecyclerView(shouldShow: Boolean) {
        binding.apply {
            recyclerView.visibility = if (shouldShow) View.VISIBLE else View.GONE
            if (shouldShow) recyclerView.scheduleLayoutAnimation()
        }
    }

    private fun <E> setSpinnerListener(spinnerView: PowerSpinnerView, func: (E) -> Unit) {
        spinnerView.setOnSpinnerItemSelectedListener<E> { _, _, _, newItem -> func(newItem) }
    }

    private fun closeSpinner() {
        binding.platformSpinner.dismiss()
        binding.sortSpinner.dismiss()
        binding.categorySpinner.dismiss()
        binding.modloaderSpinner.dismiss()
    }

    /**
     * Clear the previous search state, then search.
     */
    private fun search() {
        setStatusText(false)
        setRecyclerView(false)
        setLoadingLayout(true)
        binding.recyclerView.scrollToPosition(0)

        mTaskInProgress?.let {
            it.cancel(true)
            mTaskInProgress = null
        }
        this.mLastPage = false
        mTaskInProgress = SelfReferencingFuture(SearchApiTask(null))
            .startOnExecutor(TaskExecutors.getDefault())
    }

    /**
     * Run the search when the adapter currently holds no items.
     */
    private fun checkSearch() {
        if (mInfoAdapter.itemCount == 0) search()
    }

    @Subscribe
    fun event(event: DownloadPageEvent.RecyclerEnableEvent) {
        binding.recyclerView.isEnabled = event.enable
        closeSpinner()
    }

    @Subscribe
    fun event(event: DownloadPageEvent.PageSwapEvent) {
        closeSpinner()

        if (event.index == classify.type) {
            when (event.classify) {
                IN -> slideIn()
                OUT -> slideOut()
                else -> {}
            }
        }
    }

    @Subscribe
    fun event(event: DownloadPageEvent.PageDestroyEvent) {
        closeSpinner()
    }

    private inner class SearchApiTask(
        private val mPreviousResult: SearchResult?
    ) : SelfReferencingFuture.FutureInterface {

        override fun run(myFuture: Future<*>) {
            runCatching {
                val result: SearchResult? = mCurrentPlatform.helper.search(classify, mFilters, mPreviousResult ?: SearchResult())

                TaskExecutors.runInUIThread {
                    if (myFuture.isCancelled) return@runInUIThread
                    mTaskInProgress = null

                    when {
                        result == null -> {
                            onSearchError(ERROR_INTERNAL)
                        }
                        result.isLastPage -> {
                            if (result.infoItems.isEmpty()) {
                                onSearchError(ERROR_NO_RESULTS)
                            } else {
                                mLastPage = true
                                mInfoAdapter.setItems(result.infoItems)
                                onSearchFinished()
                                return@runInUIThread
                            }
                        }
                        else -> {
                            onSearchFinished()
                        }
                    }

                    if (result == null) {
                        mInfoAdapter.setItems(MOD_ITEMS_EMPTY)
                        return@runInUIThread
                    } else {
                        mInfoAdapter.setItems(result.infoItems)
                        mCurrentResult = result
                    }
                }
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    mInfoAdapter.setItems(MOD_ITEMS_EMPTY)
                    Logging.e("SearchTask", Tools.printToString(e))
                    if (e is PlatformNotSupportedException) {
                        onSearchError(ERROR_PLATFORM_NOT_SUPPORTED)
                    } else {
                        onSearchError(ERROR_NO_RESULTS)
                    }
                }
            }
        }
    }

    companion object {
        private val MOD_ITEMS_EMPTY: MutableList<InfoItem> = ArrayList()

        const val ERROR_INTERNAL: Int = 0
        const val ERROR_NO_RESULTS: Int = 1
        const val ERROR_PLATFORM_NOT_SUPPORTED: Int = 2
    }
}