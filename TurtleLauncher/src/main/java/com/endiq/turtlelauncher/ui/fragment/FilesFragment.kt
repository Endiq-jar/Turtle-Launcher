package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.ui.activity.ConfigEditorActivity
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentFilesBinding
import com.endiq.turtlelauncher.event.sticky.FileSelectorEvent
import com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils
import com.endiq.turtlelauncher.feature.mod.ModUpdateChecker
import com.endiq.turtlelauncher.feature.turtle.heatmap.HeatmapAnalyzer
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.EditTextDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog.FilesButton
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileItemBean
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileSelectedListener
import com.endiq.turtlelauncher.ui.subassembly.view.SearchViewWrapper
import com.endiq.turtlelauncher.utils.NewbieGuideUtils
import com.endiq.turtlelauncher.utils.StoragePermissionsUtils
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.file.PasteFile
import com.endiq.turtlelauncher.utils.file.ZipExtractUtils
import net.endiq.launcher.Tools
import net.endiq.launcher.contracts.OpenDocumentWithExtension
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Objects
import java.util.function.Consumer

class FilesFragment : FragmentWithAnim(R.layout.fragment_files) {
    companion object {
        const val TAG: String = "FilesFragment"
        const val BUNDLE_LOCK_PATH: String = "bundle_lock_path"
        const val BUNDLE_LIST_PATH: String = "bundle_list_path"
        const val BUNDLE_SHOW_FILE: String = "show_file"
        const val BUNDLE_SHOW_FOLDER: String = "show_folder"
        const val BUNDLE_QUICK_ACCESS_PATHS: String = "quick_access_paths"
        const val BUNDLE_MULTI_SELECT_MODE: String = "multi_select_mode"
        const val BUNDLE_SELECT_FOLDER_MODE: String = "select_folder_mode"
        const val BUNDLE_REMOVE_LOCK_PATH: String = "remove_lock_path"
        const val BUNDLE_TITLE_REMOVE_LOCK_PATH: String = "title_remove_lock_path"
    }

    private lateinit var binding: FragmentFilesBinding
    private lateinit var mSearchViewWrapper: SearchViewWrapper
    private var openDocumentLauncher: ActivityResultLauncher<Any?>? = null
    private var mShowFiles = false
    private var mShowFolders = false
    private var mQuickAccessPaths = false
    private var mMultiSelectMode = false
    private var mSelectFolderMode = false
    private var mRemoveLockPath = false
    private var mTitleRemoveLockPath = false
    private var mLockPath: String? = null
    private var mListPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult(OpenDocumentWithExtension(null, true)) { uris: List<Uri>? ->
            uris?.let { uriList ->
                val dialog = ZHTools.showTaskRunningDialog((requireContext()))
                // Snapshot everything the background copy needs up front: if the user
                // navigates away mid-copy, requireContext()/binding access from either
                // the worker thread or the ended callback would crash ("not attached").
                val appContext = requireContext().applicationContext
                val destPath = binding.fileRecyclerView.fullPath.absolutePath
                Task.runTask {
                    uriList.forEach { uri ->
                        FileTools.copyFileInBackground(appContext, uri, destPath)
                    }
                }.ended(TaskExecutors.getAndroidUI()) {
                    if (!isAdded) return@ended
                    Toast.makeText(requireContext(), getString(R.string.file_added), Toast.LENGTH_SHORT).show()
                    binding.fileRecyclerView.refreshPath()
                }.onThrowable { e ->
                    Tools.showErrorRemote(e)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                }.execute()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentFilesBinding.inflate(layoutInflater)
        mSearchViewWrapper = SearchViewWrapper(this)
        return binding.root
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        parseBundle()
        initViews()

        val storageDirectory = Environment.getExternalStorageDirectory()

        binding.apply {
            fileRecyclerView.apply {
                setShowFiles(mShowFiles)
                setShowFolders(mShowFolders)
                setTitleListener { title: String? -> currentPath.text = removeLockPath(title ?: "", mTitleRemoveLockPath) }

                setFileSelectedListener(object : FileSelectedListener() {
                    override fun onFileSelected(file: File?, path: String?) {
                        file?.let { showDialog(it) }
                    }

                    override fun onItemLongClick(file: File?, path: String?) {
                        file?.let { if (it.isDirectory) showDialog(it) }
                    }
                })

                setOnMultiSelectListener { itemBeans: List<FileItemBean> ->
                    if (itemBeans.isNotEmpty()) {
                        Task.runTask {
                            val selectedFiles: MutableList<File> = ArrayList()
                            itemBeans.forEach(Consumer { value: FileItemBean ->
                                val file = value.file
                                file?.apply { selectedFiles.add(this) }
                            })
                            selectedFiles
                        }.ended(TaskExecutors.getAndroidUI()) { selectedFiles ->
                            if (!isAdded) return@ended
                            val filesButton = FilesButton()
                            filesButton.setButtonVisibility(true, true, false, false, true, false)
                            filesButton.setDialogText(
                                getString(R.string.file_multi_select_mode_title),
                                getString(R.string.file_multi_select_mode_message, itemBeans.size), null
                            )

                            val filesDialog = FilesDialog(
                                requireContext(),
                                filesButton,
                                Task.runTask(TaskExecutors.getAndroidUI()) {
                                    closeMultiSelect()
                                    refreshPath()
                                },
                                fullPath,
                                selectedFiles ?: emptyList<File>()
                            )
                            filesDialog.setCopyButtonClick { operateView.pasteButton.visibility = View.VISIBLE }
                            // Zip for multi-select
                            filesDialog.setZipButtonClick { files, outputDir ->
                                handleZip(files, outputDir)
                            }
                            // Extract disabled in multi-select (handled by button alpha in dialog)
                            filesDialog.show()
                        }.execute()
                    }
                }

                setRefreshListener {
                    setVisibilityAnim(nothingText, isNoFile)
                    if (Objects.equals(fullPath.absolutePath, storageDirectory.absolutePath)) {
                        StoragePermissionsUtils.checkPermissions(
                            activity = requireActivity(),
                            title = R.string.file_external_storage,
                            permissionGranted = null
                        )
                    }
                    checkForResourceUpdates(fullPath)
                    analyzeHeatmap(fullPath)
                }
            }

            currentPath.setOnClickListener {
                EditTextDialog.Builder(requireContext())
                    .setTitle(R.string.file_jump_to_path)
                    .setEditText(fileRecyclerView.fullPath.absolutePath)
                    .setAsRequired()
                    .setConfirmListener { editBox, _ ->
                        val path = editBox.text.toString()
                        val file = File(path)
                        val lockPath = mLockPath
                        if (lockPath == null || !path.contains(lockPath) || !file.isDirectory || !file.exists()) {
                            editBox.error = getString(R.string.file_does_not_exist)
                            return@setConfirmListener false
                        }
                        fileRecyclerView.listFileAt(file)
                        true
                    }.showDialog()
            }

            externalStorage.setOnClickListener {
                closeMultiSelect()
                fileRecyclerView.listFileAt(storageDirectory)
            }

            softwarePrivate.setOnClickListener {
                closeMultiSelect()
                fileRecyclerView.listFileAt(requireContext().getExternalFilesDir(null))
            }

            val adapter = fileRecyclerView.adapter
            multiSelectFiles.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                selectAll.apply {
                    this.isChecked = false
                    visibility = if (isChecked) View.VISIBLE else View.GONE
                }
                adapter.setMultiSelectMode(isChecked)
                if (mSearchViewWrapper.isVisible()) mSearchViewWrapper.setVisibility(!isChecked)
            }
            selectAll.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                adapter.selectAllFiles(isChecked)
            }

            operateView.returnButton.setOnClickListener {
                if (!mSelectFolderMode) {
                    closeMultiSelect()
                    Tools.removeCurrentFragment(requireActivity())
                } else {
                    EventBus.getDefault().postSticky(FileSelectorEvent(
                        removeLockPath(fileRecyclerView.fullPath.absolutePath, mRemoveLockPath)
                    ))
                    Tools.removeCurrentFragment(requireActivity())
                }
            }
            operateView.addFileButton.setOnClickListener {
                closeMultiSelect()
                openDocumentLauncher?.launch(null)
            }
            operateView.createFolderButton.setOnClickListener {
                closeMultiSelect()
                EditTextDialog.Builder(requireContext())
                    .setTitle(R.string.file_folder_dialog_insert_name)
                    .setAsRequired()
                    .setConfirmListener { editBox, _ ->
                        val name = editBox.text.toString()

                        if (name.contains("/")) {
                            val folderNames = name.split("/")
                            if (folderNames.any {
                                FileTools.isFilenameInvalid(
                                    it,
                                    { illegalCharacters ->
                                        editBox.error = getString(R.string.generic_input_invalid_character, illegalCharacters)
                                    },
                                    { invalidLength ->
                                        editBox.error = getString(R.string.file_invalid_length, invalidLength, 255)
                                    }
                                )
                            }) return@setConfirmListener false
                        } else if (FileTools.isFilenameInvalid(editBox)) {
                            return@setConfirmListener false
                        }

                        val folder = File(fileRecyclerView.fullPath, name)
                        if (folder.exists()) {
                            editBox.error = getString(R.string.file_rename_exitis)
                            return@setConfirmListener false
                        }

                        val success = folder.mkdirs()
                        if (success) fileRecyclerView.listFileAt(folder)
                        else fileRecyclerView.refreshPath()
                        true
                    }.showDialog()
            }
            operateView.pasteButton.apply {
                setOnClickListener {
                    PasteFile.getInstance()
                        .pasteFiles(
                            requireActivity(),
                            fileRecyclerView.fullPath,
                            null,
                            Task.runTask(TaskExecutors.getAndroidUI()) {
                                closeMultiSelect()
                                visibility = View.GONE
                                fileRecyclerView.refreshPath()
                            }
                        )
                }
            }
            operateView.searchButton.setOnClickListener {
                closeMultiSelect()
                mSearchViewWrapper.setVisibility()
            }
            operateView.refreshButton.setOnClickListener {
                closeMultiSelect()
                fileRecyclerView.refreshPath()
            }

            fileRecyclerView.apply list@{
                // mLockPath is null when this fragment was created without arguments -
                // bail out instead of NPEing on File(null).
                val lockPath = mLockPath ?: return@list
                val listPath = mListPath
                if (listPath != null) {
                    lockAndListAt(File(lockPath), File(listPath))
                } else {
                    lockAndListAt(File(lockPath), File(lockPath))
                }
            }
        }

        startNewbieGuide()
    }

    // ── Zip / Extract handlers ───────────────────────────────────────────────

    /**
     * Show a name-input dialog, then compress [files] into a .zip next to them.
     */
    /**
     * TurtleLauncher: whenever a mods/resourcepacks/shaderpacks folder is opened (or
     * refreshed), hash whatever's in it against Modrinth and dot-badge anything with a
     * newer version available. Silently does nothing for any other folder, or if we
     * can't tell which installed version this one belongs to.
     */
    private fun checkForResourceUpdates(folder: File) {
        val category = folder.name.lowercase()
        if (category != "mods" && category != "resourcepacks" && category != "shaderpacks") return

        val adapter = binding.fileRecyclerView.adapter ?: return
        val files = adapter.data.mapNotNull { it.file }.filter { it.isFile }
        if (files.isEmpty()) return

        Task.runTask {
            runCatching {
                val version = VersionsManager.getCurrentVersion() ?: return@runCatching emptyList()
                val versionInfo = version.getVersionInfo() ?: return@runCatching emptyList()
                val mcVersion = versionInfo.minecraftVersion ?: return@runCatching emptyList()
                // Only mods care about loader compatibility — resource packs and shader
                // packs aren't loader-specific, so leave it null and check across all of them.
                val loader = if (category == "mods") {
                    versionInfo.loaderInfo?.firstNotNullOfOrNull { ModLoaderUtils.getModLoader(it.name) }
                } else null

                ModUpdateChecker.checkForFileUpdates(files, mcVersion, loader)
            }.getOrDefault(emptyList())
        }.ended(TaskExecutors.getAndroidUI()) { updates ->
            val updatedPaths = updates.orEmpty().map { it.currentFile.absolutePath }.toSet()
            if (updatedPaths.isEmpty()) return@ended

            var changed = false
            adapter.data.forEach { item ->
                val hasUpdate = item.file?.absolutePath in updatedPaths
                if (item.hasUpdate != hasUpdate) {
                    item.hasUpdate = hasUpdate
                    changed = true
                }
            }
            if (changed) adapter.notifyDataSetChanged()
        }.execute()
    }

    /**
     * TurtleLauncher: Performance Heatmap (Section 11) - runs HeatmapAnalyzer over
     * whatever's in a mods/resourcepacks/shaderpacks folder and stamps each row's
     * FileItemBean.perfEstimate, same shape as checkForResourceUpdates above (async
     * Task, mutate the already-bound adapter data, notify only if something changed).
     * Independent of the update check - a file can have both badges at once.
     */
    private fun analyzeHeatmap(folder: File) {
        if (!AllSettings.performanceHeatmapEnabled.getValue()) return
        val category = folder.name.lowercase()
        if (category != "mods" && category != "resourcepacks" && category != "shaderpacks") return

        val adapter = binding.fileRecyclerView.adapter ?: return
        val items = adapter.data.filter { it.file != null }
        if (items.isEmpty()) return

        Task.runTask {
            items.mapNotNull { item ->
                val file = item.file ?: return@mapNotNull null
                HeatmapAnalyzer.analyze(file, category)?.let { estimate -> item to estimate }
            }
        }.ended(TaskExecutors.getAndroidUI()) { results ->
            var changed = false
            results.orEmpty().forEach { (item, estimate) ->
                item.perfEstimate = estimate
                changed = true
            }
            if (changed) adapter.notifyDataSetChanged()
        }.execute()
    }

    private fun handleZip(files: List<File>, outputDir: File) {
        val ctx = context ?: return
        // Suggest archive name from first item
        val suggested = files.firstOrNull()?.nameWithoutExtension ?: "archive"

        EditTextDialog.Builder(ctx)
            .setTitle(R.string.file_zip_enter_name)
            .setEditText(suggested)
            .setAsRequired()
            .setConfirmListener { editBox, _ ->
                val name = editBox.text.toString().trim()
                if (name.isEmpty()) {
                    editBox.error = getString(R.string.file_rename_exitis)
                    return@setConfirmListener false
                }

                val destZip = run {
                    var candidate = File(outputDir, "$name.zip")
                    var i = 1
                    while (candidate.exists()) { candidate = File(outputDir, "${name}_$i.zip"); i++ }
                    candidate
                }

                val progressDialog = ZHTools.showTaskRunningDialog(ctx)
                Task.runTask {
                    ZipExtractUtils.compress(files, destZip)
                }.ended(TaskExecutors.getAndroidUI()) {
                    // ctx is the captured Context (safe), but getString() would go
                    // through requireContext() and crash if the user navigated away
                    // mid-compression - resolve strings from ctx instead.
                    if (!isAdded) return@ended
                    Toast.makeText(ctx,
                        ctx.getString(R.string.file_zip_success, destZip.name),
                        Toast.LENGTH_SHORT).show()
                    binding.fileRecyclerView.refreshPath()
                }.onThrowable { e ->
                    TaskExecutors.getAndroidUI().execute {
                        Toast.makeText(ctx,
                            ctx.getString(R.string.file_zip_failed, e.message ?: "Unknown error"),
                            Toast.LENGTH_LONG).show()
                    }
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    progressDialog.dismiss()
                }.execute()

                true
            }.showDialog()
    }

    /**
     * Extract [zipFile] into a subfolder next to it, then refresh the list.
     */
    private fun handleExtract(zipFile: File, outputDir: File) {
        val ctx = context ?: return

        if (!zipFile.name.lowercase().endsWith(".zip")) {
            Toast.makeText(ctx, R.string.file_extract_not_zip, Toast.LENGTH_SHORT).show()
            return
        }

        val destDir = ZipExtractUtils.suggestExtractDir(zipFile)
        val progressDialog = ZHTools.showTaskRunningDialog(ctx)

        Task.runTask {
            ZipExtractUtils.extract(zipFile, destDir)
        }.ended(TaskExecutors.getAndroidUI()) {
            // Same detach hazard as handleZip above: resolve strings from the
            // captured ctx, and skip the list refresh once detached.
            if (!isAdded) return@ended
            Toast.makeText(ctx,
                ctx.getString(R.string.file_extract_success, destDir.name),
                Toast.LENGTH_SHORT).show()
            binding.fileRecyclerView.refreshPath()
        }.onThrowable { e ->
            TaskExecutors.getAndroidUI().execute {
                Toast.makeText(ctx,
                    ctx.getString(R.string.file_extract_failed, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG).show()
            }
        }.finallyTask(TaskExecutors.getAndroidUI()) {
            progressDialog.dismiss()
        }.execute()
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne("${TAG}${if (mSelectFolderMode) "_select" else ""}")) return
        binding.operateView.apply {
            val fragmentActivity = requireActivity()
            val refresh = NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh))
            val search = NewbieGuideUtils.getSimpleTarget(fragmentActivity, searchButton, getString(R.string.generic_search), getString(R.string.newbie_guide_file_search))
            val createFolder = NewbieGuideUtils.getSimpleTarget(fragmentActivity, createFolderButton, getString(R.string.file_create_folder), getString(R.string.newbie_guide_file_create_folder))
            if (mSelectFolderMode) {
                TapTargetSequence(fragmentActivity)
                    .targets(refresh, search, createFolder,
                        NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.file_select_folder), getString(R.string.newbie_guide_file_select)))
                    .start()
            } else {
                TapTargetSequence(fragmentActivity)
                    .targets(refresh, search,
                        NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.file_add_file), getString(R.string.newbie_guide_file_import)),
                        createFolder,
                        NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_close), getString(R.string.newbie_guide_general_close)))
                    .start()
            }
        }
    }

    private fun closeMultiSelect() {
        binding.multiSelectFiles.isChecked = false
        binding.selectAll.visibility = View.GONE
    }

    private fun showDialog(file: File) {
        val filesButton = FilesButton()
        filesButton.setButtonVisibility(true, true, true, true, true, false)
        val message = if (file.isDirectory) getString(R.string.file_folder_message)
                      else getString(R.string.file_message)
        filesButton.setMessageText(message)

        val filesDialog = FilesDialog(
            requireContext(), filesButton,
            Task.runTask(TaskExecutors.getAndroidUI()) {
                binding.fileRecyclerView.refreshPath()
            },
            binding.fileRecyclerView.fullPath, file
        )
        filesDialog.setCopyButtonClick { binding.operateView.pasteButton.visibility = View.VISIBLE }

        // Wire zip: single item
        filesDialog.setZipButtonClick { files, outputDir ->
            handleZip(files, outputDir)
        }
        // Wire extract: single .zip file only
        filesDialog.setExtractButtonClick { zipFile, outputDir ->
            handleExtract(zipFile, outputDir)
        }
        // Wire edit: open the file in the in-launcher text editor
        filesDialog.setEditButtonClick { targetFile ->
            val intent = Intent(requireContext(), ConfigEditorActivity::class.java)
            intent.putExtra(ConfigEditorActivity.EXTRA_TARGET_FILE_PATH, targetFile.absolutePath)
            startActivity(intent)
        }

        filesDialog.show()
    }

    private fun removeLockPath(path: String, remove: Boolean): String {
        var string = path
        val lockPath = mLockPath
        if (remove && lockPath != null) string = path.replace(lockPath, ".")
        return string
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun initViews() {
        binding.apply {
            mSearchViewWrapper.setSearchListener(object : SearchViewWrapper.SearchListener {
                override fun onSearch(string: String?, caseSensitive: Boolean): Int {
                    return fileRecyclerView.searchFiles(string, caseSensitive)
                }
            })
            mSearchViewWrapper.setShowSearchResultsListener(object : SearchViewWrapper.ShowSearchResultsListener {
                override fun onSearch(show: Boolean) {
                    fileRecyclerView.setShowSearchResultsOnly(show)
                }
            })

            // TurtleLauncher: these quick-access shortcuts used to be hidden whenever this
            // browser was locked to a specific subfolder (mods/resourcepacks/shaderpacks/
            // saves), i.e. everywhere except the main, unrestricted Files screen. Always
            // showing them lets you jump straight to external or private app storage from
            // any of those screens too, e.g. to grab a mod file from Downloads.
            if (mSelectFolderMode || !mMultiSelectMode) {
                multiSelectFiles.visibility = View.GONE
                selectAll.visibility = View.GONE
            }

            if (mSelectFolderMode) {
                operateView.addFileButton.visibility = View.GONE
                operateView.returnButton.apply {
                    contentDescription = getString(R.string.file_select_folder)
                    setImageDrawable(resources.getDrawable(R.drawable.ic_check, requireActivity().theme))
                }
            }
        }

        binding.operateView.apply {
            pasteButton.setVisibility(if (PasteFile.getInstance().pasteType != null) View.VISIBLE else View.GONE)

            ZHTools.setTooltipText(
                returnButton,
                addFileButton,
                createFolderButton,
                pasteButton,
                searchButton,
                refreshButton)
        }
    }

    private fun parseBundle() {
        val bundle = arguments ?: return
        mLockPath = bundle.getString(BUNDLE_LOCK_PATH, Environment.getExternalStorageDirectory().absolutePath)
        mListPath = bundle.getString(BUNDLE_LIST_PATH, null)
        mShowFiles = bundle.getBoolean(BUNDLE_SHOW_FILE, true)
        mShowFolders = bundle.getBoolean(BUNDLE_SHOW_FOLDER, true)
        mQuickAccessPaths = bundle.getBoolean(BUNDLE_QUICK_ACCESS_PATHS, true)
        mMultiSelectMode = bundle.getBoolean(BUNDLE_MULTI_SELECT_MODE, true)
        mSelectFolderMode = bundle.getBoolean(BUNDLE_SELECT_FOLDER_MODE, false)
        mRemoveLockPath = bundle.getBoolean(BUNDLE_REMOVE_LOCK_PATH, true)
        mTitleRemoveLockPath = bundle.getBoolean(BUNDLE_TITLE_REMOVE_LOCK_PATH, true)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(filesLayout, TurtleTransitions.enter()))
                .apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.enter()))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(filesLayout, TurtleTransitions.exit()))
                .apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.exit()))
        }
    }
}
