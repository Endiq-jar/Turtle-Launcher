package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.FragmentModsBinding
import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils
import com.endiq.turtlelauncher.feature.mod.ModToggleHandler
import com.endiq.turtlelauncher.feature.mod.ModUtils
import com.endiq.turtlelauncher.feature.mod.RecommendedContentInstaller
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.FilesDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog.FilesButton
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileIcon
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileItemBean
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileSelectedListener
import com.endiq.turtlelauncher.ui.subassembly.view.SearchViewWrapper
import com.endiq.turtlelauncher.utils.NewbieGuideUtils
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.endiq.turtlelauncher.utils.file.FileCopyHandler
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.file.PasteFile
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import java.io.File
import java.util.function.Consumer

class ModsFragment : FragmentWithAnim(R.layout.fragment_mods) {
    companion object {
        const val TAG: String = "ModsFragment"
        const val BUNDLE_ROOT_PATH: String = "root_path"
    }

    private lateinit var binding: FragmentModsBinding
    private lateinit var mSearchViewWrapper: SearchViewWrapper
    private lateinit var mRootPath: String
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Any>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult(OpenDocumentWithExtension("jar", true)) { uris: List<Uri>? ->
            uris?.let { uriList ->
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                // Snapshot the context up front: requireContext() from the worker
                // thread would crash if the user navigates away mid-copy.
                val appContext = requireContext().applicationContext
                Task.runTask {
                    uriList.forEach { uri ->
                        FileTools.copyFileInBackground(appContext, uri, mRootPath)
                    }
                }.ended(TaskExecutors.getAndroidUI()) {
                    if (!isAdded) return@ended
                    Toast.makeText(requireContext(), getString(R.string.profile_mods_added_mod), Toast.LENGTH_SHORT).show()
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
        binding = FragmentModsBinding.inflate(layoutInflater)
        mSearchViewWrapper = SearchViewWrapper(this)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews()
        parseBundle()

        binding.apply {
            fileRecyclerView.apply {
                setShowFiles(true)
                setShowFolders(false)

                setFileSelectedListener(object : FileSelectedListener() {
                    override fun onFileSelected(file: File?, path: String?) {
                        file?.let {
                            if (it.isFile) {
                                val fileName = it.name

                                val filesButton = FilesButton()
                                filesButton.setButtonVisibility(true, true, true, true, true,
                                    (fileName.endsWith(ModUtils.JAR_FILE_SUFFIX) || fileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)))
                                filesButton.setMessageText(if (it.isDirectory) getString(R.string.file_folder_message) else getString(R.string.file_message))

                                if (fileName.endsWith(ModUtils.JAR_FILE_SUFFIX)) filesButton.setMoreButtonText(getString(R.string.profile_mods_disable))
                                else if (fileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)) filesButton.setMoreButtonText(getString(R.string.profile_mods_enable))

                                val filesDialog = FilesDialog(requireContext(), filesButton,
                                    Task.runTask(TaskExecutors.getAndroidUI()) { refreshPath() },
                                    fullPath, it
                                )

                                filesDialog.setCopyButtonClick { visibility = View.VISIBLE }

                                // Detect the file extension to set up the right button.
                                if (fileName.endsWith(ModUtils.JAR_FILE_SUFFIX)) {
                                    filesDialog.setFileSuffix(ModUtils.JAR_FILE_SUFFIX)
                                    filesDialog.setMoreButtonClick {
                                        ModUtils.disableMod(it)
                                        refreshPath()
                                        filesDialog.dismiss()
                                    }
                                } else if (fileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)) {
                                    filesDialog.setFileSuffix(ModUtils.DISABLE_JAR_FILE_SUFFIX)
                                    filesDialog.setMoreButtonClick {
                                        ModUtils.enableMod(it)
                                        refreshPath()
                                        filesDialog.dismiss()
                                    }
                                }

                                filesDialog.show()
                            }
                        }
                    }

                    override fun onItemLongClick(file: File?, path: String?) {
                    }
                })

                setOnMultiSelectListener { itemBeans: List<FileItemBean> ->
                    if (itemBeans.isNotEmpty()) {
                        Task.runTask {
                            // Extract every file.
                            val selectedFiles: MutableList<File> = ArrayList()
                            itemBeans.forEach(Consumer { value: FileItemBean ->
                                val file = value.file
                                file?.apply { selectedFiles.add(this) }
                            })
                            selectedFiles
                        }.ended(TaskExecutors.getAndroidUI()) { selectedFiles ->
                            if (!isAdded) return@ended
                            val files = selectedFiles ?: return@ended
                            val filesButton = FilesButton()
                            filesButton.setButtonVisibility(true, true, false, false, true, true)
                            filesButton.setDialogText(
                                getString(R.string.file_multi_select_mode_title),
                                getString(R.string.file_multi_select_mode_message, itemBeans.size),
                                getString(R.string.profile_mods_disable_or_enable)
                            )

                            val filesDialog = FilesDialog(requireContext(), filesButton,
                                Task.runTask(TaskExecutors.getAndroidUI()) {
                                    closeMultiSelect()
                                    refreshPath()
                                }, fullPath, files)
                            filesDialog.setCopyButtonClick { operateView.pasteButton.visibility = View.VISIBLE }
                            filesDialog.setMoreButtonClick {
                                ModToggleHandler(requireContext(), files,
                                    Task.runTask(TaskExecutors.getAndroidUI()) {
                                        closeMultiSelect()
                                        refreshPath()
                                    }).start()
                            }
                            filesDialog.show()
                        }.execute()
                    }
                }

                setRefreshListener {
                    setVisibilityAnim(nothingLayout, isNoFile)
                }
            }

            multiSelectFiles.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                selectAll.apply {
                    this.isChecked = false
                    visibility = if (isChecked) View.VISIBLE else View.GONE
                }
                fileRecyclerView.adapter.setMultiSelectMode(isChecked)
                mSearchViewWrapper.let { if (mSearchViewWrapper.isVisible()) mSearchViewWrapper.setVisibility(!isChecked) }
            }

            externalStorage.setOnClickListener {
                closeMultiSelect()
                fileRecyclerView.listFileAt(android.os.Environment.getExternalStorageDirectory())
            }

            softwarePrivate.setOnClickListener {
                closeMultiSelect()
                fileRecyclerView.listFileAt(requireContext().getExternalFilesDir(null))
            }

            operateView.apply {
                selectAll.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                    fileRecyclerView.adapter.selectAllFiles(isChecked)
                }

                returnButton.setOnClickListener {
                    closeMultiSelect()
                    ZHTools.onBackPressed(requireActivity())
                }

                addFileButton.setOnClickListener {
                    closeMultiSelect()
                    val suffix = ".jar"
                    Toast.makeText(
                        requireActivity(),
                        String.format(getString(R.string.file_add_file_tip), suffix),
                        Toast.LENGTH_SHORT
                    ).show()
                    openDocumentLauncher.launch(suffix)
                }

                pasteButton.setOnClickListener {
                    PasteFile.getInstance().pasteFiles(
                        requireActivity(),
                        fileRecyclerView.fullPath,
                        object : FileCopyHandler.FileExtensionGetter {
                            override fun onGet(file: File?): String? {
                                return file?.let { it1 -> getFileSuffix(it1) }
                            }
                        },
                        Task.runTask(TaskExecutors.getAndroidUI()) {
                            closeMultiSelect()
                            pasteButton.visibility = View.GONE
                            fileRecyclerView.refreshPath()
                        }
                    )
                }

                createFolderButton.setOnClickListener { goDownloadMod() }
                createFolderButton.setOnLongClickListener { confirmInstallRecommended(); true }

                searchButton.setOnClickListener {
                    closeMultiSelect()
                    mSearchViewWrapper.setVisibility()
                }

                refreshButton.setOnClickListener {
                    closeMultiSelect()
                    fileRecyclerView.refreshPath()
                }
            }

            goDownloadText.setOnClickListener{ goDownloadMod() }

            fileRecyclerView.lockAndListAt(File(mRootPath), File(mRootPath))
        }

        startNewbieGuide()
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        binding.operateView.apply {
            val fragmentActivity = requireActivity()
            TapTargetSequence(fragmentActivity)
                .targets(
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, searchButton, getString(R.string.generic_search), getString(R.string.newbie_guide_mod_search)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.profile_mods_add_mod), getString(R.string.newbie_guide_mod_import)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, createFolderButton, getString(R.string.profile_mods_download_mod), getString(R.string.newbie_guide_mod_download)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_close), getString(R.string.newbie_guide_general_close)))
                .start()
        }
    }

    private fun closeMultiSelect() {
        // Leave multi-select mode when another control is tapped.
        binding.apply {
            multiSelectFiles.isChecked = false
            selectAll.visibility = View.GONE
        }
    }

    private fun getFileSuffix(file: File): String {
        val name = file.name
        if (name.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)) {
            return ModUtils.DISABLE_JAR_FILE_SUFFIX
        } else if (name.endsWith(ModUtils.JAR_FILE_SUFFIX)) {
            return ModUtils.JAR_FILE_SUFFIX
        } else {
            val dotIndex = file.name.lastIndexOf('.')
            return if (dotIndex == -1) "" else file.name.substring(dotIndex)
        }
    }

    private fun goDownloadMod() {
        closeMultiSelect()
        ZHTools.swapFragmentWithAnim(
            this,
            DownloadFragment::class.java,
            DownloadFragment.TAG,
            null
        )
    }

    private fun confirmInstallRecommended() {
        closeMultiSelect()
        val context = requireContext()
        val version = resolveVersionForModsRoot()
        val versionInfo = version?.getVersionInfo()
        val mcVersion = versionInfo?.minecraftVersion
        if (version == null || mcVersion == null) {
            Toast.makeText(context, "Couldn't determine this instance's Minecraft version", Toast.LENGTH_SHORT).show()
            return
        }
        val loader = versionInfo.loaderInfo?.firstNotNullOfOrNull { ModLoaderUtils.getModLoader(it.name) }

        TipDialog.Builder(context)
            .setTitle("Install recommended mods?")
            .setMessage(
                "Fabric API, Sodium, Lithium, Entity Culling, Indium (if needed), FerriteCore, " +
                "ModernFix, ImmediatelyFast, and the Bare Bones resource pack — matched to " +
                "Minecraft $mcVersion${loader?.let { " / ${it.loaderName}" } ?: ""}. " +
                "Anything not built for this loader is skipped automatically."
            )
            .setShowCancel(true)
            .setConfirm(R.string.generic_ok)
            .setConfirmClickListener { _ -> runInstallRecommended(version, mcVersion, loader) }
            .showDialog()
    }

    private fun runInstallRecommended(version: Version, mcVersion: String, loader: ModLoader?) {
        val dialog = ZHTools.showTaskRunningDialog(requireContext())
        Task.runTask {
            val gameDir = version.getGameDir()
            RecommendedContentInstaller.installAll(
                mcVersion, loader,
                modsDir = File(gameDir, "mods"),
                resourcePacksDir = File(gameDir, "resourcepacks")
            )
        }.ended(TaskExecutors.getAndroidUI()) { results ->
            dialog.dismiss()
            if (!isAdded) return@ended
            binding.fileRecyclerView.refreshPath()
            showInstallResultDialog(results ?: emptyList())
        }.onThrowable { e ->
            TaskExecutors.getAndroidUI().execute {
                dialog.dismiss()
                Tools.showErrorRemote(e)
            }
        }.execute()
    }

    private fun showInstallResultDialog(results: List<RecommendedContentInstaller.InstallOutcome>) {
        if (results.isEmpty()) return
        val message = buildString {
            results.forEach { outcome ->
                append(if (outcome.success) "✓ " else "✗ ")
                append(outcome.entry.displayName).append(": ").append(outcome.message).append('\n')
            }
        }.trim()

        TipDialog.Builder(requireContext())
            .setTitle("Recommended install")
            .setMessage(message)
            .setCenterMessage(false)
            .setSelectable(true)
            .setShowCancel(false)
            .setConfirm(R.string.generic_ok)
            .showDialog()
    }

    /** mRootPath is always the instance's own mods/ folder (set once from the bundle, never
     *  reassigned as the user browses subfolders), so its parent folder name is the version. */
    private fun resolveVersionForModsRoot(): Version? {
        val versionName = File(mRootPath).parentFile?.name ?: return null
        return VersionsManager.getVersions().firstOrNull { it.getVersionName() == versionName }
    }

    private fun parseBundle() {
        val bundle = arguments ?: throw NullPointerException("The argument is null!")
        mRootPath = bundle.getString(BUNDLE_ROOT_PATH) ?: throw IllegalStateException("root path is not set！")
    }

    private fun initViews() {
        binding.apply {
            mSearchViewWrapper.apply {
                setSearchListener(object : SearchViewWrapper.SearchListener {
                    override fun onSearch(string: String?, caseSensitive: Boolean): Int {
                        return fileRecyclerView.searchFiles(string, caseSensitive)
                    }
                })
                setShowSearchResultsListener(object : SearchViewWrapper.ShowSearchResultsListener {
                    override fun onSearch(show: Boolean) {
                        fileRecyclerView.setShowSearchResultsOnly(show)
                    }
                })
            }

            fileRecyclerView.setFileIcon(FileIcon.MOD)

            operateView.apply {
                addFileButton.setContentDescription(getString(R.string.profile_mods_add_mod))
                createFolderButton.setContentDescription(getString(R.string.profile_mods_download_mod))
                createFolderButton.setImageDrawable(
                    ContextCompat.getDrawable(
                        requireContext(),
                        R.drawable.ic_download
                    )
                )
                pasteButton.setVisibility(if (PasteFile.getInstance().pasteType != null) View.VISIBLE else View.GONE)

                ZHTools.setTooltipText(
                    returnButton,
                    addFileButton,
                    pasteButton,
                    createFolderButton,
                    searchButton,
                    refreshButton
                )
            }
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(modsLayout, TurtleTransitions.enter()))
                .apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.enter()))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(modsLayout, TurtleTransitions.exit()))
                .apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.exit()))
        }
    }
}

