package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.FragmentControlManagerBinding
import com.endiq.turtlelauncher.event.sticky.FileSelectorEvent
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.EditControlInfoDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog.FilesButton
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.subassembly.customcontrols.ControlInfoData
import com.endiq.turtlelauncher.ui.subassembly.customcontrols.ControlSelectedListener
import com.endiq.turtlelauncher.ui.subassembly.customcontrols.ControlsListViewCreator
import com.endiq.turtlelauncher.ui.subassembly.customcontrols.EditControlData.Companion.createNewControlFile
import com.endiq.turtlelauncher.ui.subassembly.view.SearchViewWrapper
import com.endiq.turtlelauncher.utils.NewbieGuideUtils
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.file.PasteFile
import net.kdt.pojavlaunch.CustomControlsActivity
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import org.greenrobot.eventbus.EventBus
import java.io.File

class ControlButtonFragment : FragmentWithAnim(R.layout.fragment_control_manager) {
    companion object {
        const val TAG: String = "ControlButtonFragment"
        const val BUNDLE_SELECT_CONTROL: String = "bundle_select_control"
    }

    private lateinit var binding: FragmentControlManagerBinding
    private lateinit var controlsListViewCreator: ControlsListViewCreator
    private lateinit var mSearchViewWrapper: SearchViewWrapper
    private var openDocumentLauncher: ActivityResultLauncher<Any>? = null
    private var mSelectControl = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult(OpenDocumentWithExtension("json", true)) { uris: List<Uri>? ->
            uris?.let { uriList ->
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                // Snapshot the context up front: if the user navigates away mid-copy,
                // requireContext() from the worker thread or the ended callback would
                // crash ("not attached to a context").
                val appContext = requireContext().applicationContext
                Task.runTask {
                    uriList.forEach { uri ->
                        FileTools.copyFileInBackground(appContext, uri, File(PathManager.DIR_CTRLMAP_PATH).absolutePath)
                    }
                }.ended(TaskExecutors.getAndroidUI()) {
                    if (!isAdded) return@ended
                    Toast.makeText(requireContext(), getString(R.string.file_added), Toast.LENGTH_SHORT).show()
                    controlsListViewCreator.refresh()
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
        binding = FragmentControlManagerBinding.inflate(layoutInflater)
        controlsListViewCreator = ControlsListViewCreator(requireContext(), binding.recyclerView)
        mSearchViewWrapper = SearchViewWrapper(this)
        return binding.root
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews()
        parseBundle()

        controlsListViewCreator.apply {
            setSelectedListener(object : ControlSelectedListener() {
                override fun onItemSelected(file: File) {
                    if (mSelectControl) {
                        EventBus.getDefault().postSticky(FileSelectorEvent(removeLockPath(file.absolutePath)))
                        Tools.removeCurrentFragment(requireActivity())
                    } else {
                        if (file.isFile) showDialog(file)
                    }
                }

                override fun onItemLongClick(file: File) {
                    TipDialog.Builder(requireContext())
                        .setTitle(R.string.pedit_control)
                        .setMessage(R.string.controls_set_default_message)
                        .setConfirmClickListener {
                            val absolutePath = file.absolutePath
                            AllSettings.defaultCtrl.put(absolutePath).save()
                        }.showDialog()
                }
            })

            setRefreshListener {
                val show = itemCount == 0
                setVisibilityAnim(binding.nothingText, show)
            }
        }

        binding.operateView.apply {
            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }

            pasteButton.setOnClickListener {
                PasteFile.getInstance().pasteFiles(
                    requireActivity(),
                    File(PathManager.DIR_CTRLMAP_PATH),
                    null,
                    Task.runTask(TaskExecutors.getAndroidUI()) {
                        pasteButton.visibility = View.GONE
                        controlsListViewCreator.refresh()
                    }
                )
            }

            addFileButton.setOnClickListener {
                val suffix = ".json"
                Toast.makeText(requireActivity(), String.format(getString(R.string.file_add_file_tip), suffix), Toast.LENGTH_SHORT).show()
                openDocumentLauncher?.launch(suffix)
            } // only .json files

            createFolderButton.setOnClickListener {
                val editControlInfoDialog = EditControlInfoDialog(requireContext(), true, null, ControlInfoData())
                editControlInfoDialog.setTitle(getString(R.string.controls_create_new))
                editControlInfoDialog.setOnConfirmClickListener { fileName: String, controlInfoData: ControlInfoData ->
                    val file = File(File(PathManager.DIR_CTRLMAP_PATH).absolutePath, "$fileName.json")
                    if (file.exists()) { // check whether the file is already there
                        editControlInfoDialog.fileNameEditBox.error =
                            getString(R.string.file_rename_exitis)
                        return@setOnConfirmClickListener
                    }

                    // Create the layout file.
                    createNewControlFile(requireContext(), file, controlInfoData)

                    controlsListViewCreator.refresh()
                    editControlInfoDialog.dismiss()
                }
                editControlInfoDialog.show()
            }

            searchButton.setOnClickListener { mSearchViewWrapper.setVisibility() }
            refreshButton.setOnClickListener { controlsListViewCreator.refresh() }
        }

        controlsListViewCreator.listAtPath()

        startNewbieGuide()
    }

    private fun removeLockPath(path: String?): String {
        return (path ?: "").replace(PathManager.DIR_CTRLMAP_PATH, ".")
    }

    private fun showDialog(file: File) {
        val filesButton = FilesButton()
        filesButton.setButtonVisibility(true, true, true, true, true, true)
        filesButton.setMessageText(getString(R.string.file_message))
        filesButton.setMoreButtonText(getString(R.string.generic_edit))

        val filesDialog = FilesDialog(requireContext(), filesButton,
            Task.runTask(TaskExecutors.getAndroidUI()) {
                controlsListViewCreator.refresh()
            },
            controlsListViewCreator.fullPath, file
        )

        filesDialog.setCopyButtonClick { binding.operateView.pasteButton.visibility = View.VISIBLE }

        filesDialog.setMoreButtonClick {
            if (!isTaskRunning()) {
                val intent = Intent(requireContext(), CustomControlsActivity::class.java)
                val bundle = Bundle()
                bundle.putString(CustomControlsActivity.BUNDLE_CONTROL_PATH, file.absolutePath)
                intent.putExtras(bundle)

                startActivity(intent)
            } else {
                Toast.makeText(requireActivity(), getString(R.string.tasks_ongoing), Toast.LENGTH_SHORT).show()
            }
            filesDialog.dismiss()
        } // load
        filesDialog.show()
    }

    private fun parseBundle() {
        val bundle = arguments ?: return
        mSelectControl = bundle.getBoolean(BUNDLE_SELECT_CONTROL, mSelectControl)
    }

    private fun initViews() {
        mSearchViewWrapper.apply {
            setAsynchronousUpdatesListener(object : SearchViewWrapper.SearchAsynchronousUpdatesListener {
                override fun onSearch(searchCount: TextView?, string: String?, caseSensitive: Boolean) {
                    controlsListViewCreator.searchControls(searchCount, string, caseSensitive)
                }
            })

            setShowSearchResultsListener(object : SearchViewWrapper.ShowSearchResultsListener {
                override fun onSearch(show: Boolean) {
                    controlsListViewCreator.setShowSearchResultsOnly(show)
                }
            })
        }

        binding.operateView.apply {
            addFileButton.setContentDescription(getString(R.string.controls_import_control))
            createFolderButton.setContentDescription(getString(R.string.controls_create_new))

            pasteButton.setVisibility(if (PasteFile.getInstance().pasteType != null) View.VISIBLE else View.GONE)

            ZHTools.setTooltipText(
                returnButton,
                addFileButton,
                createFolderButton,
                pasteButton,
                searchButton,
                refreshButton
            )
        }
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        binding.operateView.apply {
            val fragmentActivity = requireActivity()
            TapTargetSequence(fragmentActivity)
                .targets(
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, searchButton, getString(R.string.generic_search), getString(R.string.newbie_guide_control_search)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.controls_import_control), getString(R.string.newbie_guide_control_import)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, createFolderButton, getString(R.string.controls_create_new), getString(R.string.newbie_guide_control_create)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_return), getString(R.string.newbie_guide_general_close)))
                .start()
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.controlLayout, TurtleTransitions.enter()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.controlLayout, TurtleTransitions.exit()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.exit()))
    }
}

