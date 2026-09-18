package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentCustomBackgroundBinding
import com.endiq.turtlelauncher.event.single.MainBackgroundChangeEvent
import com.endiq.turtlelauncher.feature.background.BackgroundManager
import com.endiq.turtlelauncher.feature.background.BackgroundManager.NULL
import com.endiq.turtlelauncher.feature.background.BackgroundType
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.FilesDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog.FilesButton
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileIcon
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileSelectedListener
import com.endiq.turtlelauncher.utils.NewbieGuideUtils
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.mkdirs
import com.endiq.turtlelauncher.utils.image.ImageUtils.Companion.isImage
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.endiq.launcher.Tools
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.EnumMap

class CustomBackgroundFragment : FragmentWithAnim(R.layout.fragment_custom_background) {
    companion object {
        const val TAG: String = "CustomBackgroundFragment"
    }

    private lateinit var binding: FragmentCustomBackgroundBinding
    private val backgroundMap: MutableMap<BackgroundType, String> = EnumMap(BackgroundType::class.java)
    private var openDocumentLauncher: ActivityResultLauncher<Array<String>>? = null
    private var backgroundType: BackgroundType = BackgroundType.MAIN_MENU

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult<Array<String>, Uri>(ActivityResultContracts.OpenDocument()) { result: Uri? ->
            result?.let { uri ->
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                // Snapshot everything the background copy needs up front: if the user
                // navigates away mid-copy, requireActivity()/binding access from either
                // the worker thread or the ended callback would crash ("not attached").
                val appContext = requireContext().applicationContext
                val destPath = binding.fileRecyclerView.fullPath.absolutePath
                Task.runTask {
                    FileTools.copyFileInBackground(appContext, uri, destPath)
                }.ended(TaskExecutors.getAndroidUI()) {
                    if (!isAdded) return@ended
                    Toast.makeText(requireActivity(), getString(R.string.file_added), Toast.LENGTH_SHORT).show()
                    binding.fileRecyclerView.listFileAt(backgroundPath())
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
        binding = FragmentCustomBackgroundBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews()
        initBackgroundMap()
        bindTabs()

        binding.apply {
            fileRecyclerView.apply {
                setShowFiles(true)
                setShowFolders(false)

                setFileSelectedListener(object : FileSelectedListener() {
                    override fun onFileSelected(file: File?, path: String?) {
                        val fileName = file?.name ?: return

                        val image = isImage(file)
                        val filesButton = FilesButton()
                        filesButton.setButtonVisibility(false, false, true, true, true, image)
                        // The default virtual mouse cannot be shared, renamed or deleted
                        val message = if (image) { // not an image: show the default file-picker hint
                            getString(R.string.custom_background_dialog_message, currentStatusName)
                        } else {
                            getString(R.string.file_message)
                        }

                        filesButton.setMessageText(message)
                        filesButton.setMoreButtonText(getString(R.string.generic_select))

                        val filesDialog = FilesDialog(requireActivity(), filesButton,
                            Task.runTask(TaskExecutors.getAndroidUI()) {
                                refreshPath()
                            },
                            backgroundPath(), file)
                        filesDialog.setMoreButtonClick {
                            backgroundMap[backgroundType] = fileName
                            BackgroundManager.saveProperties(backgroundMap)
                            refreshBackground()

                            Toast.makeText(requireActivity(),
                                StringUtils.insertSpace(getString(R.string.custom_background_selected, currentStatusName), fileName),
                                Toast.LENGTH_SHORT
                            ).show()
                            filesDialog.dismiss()
                        }
                        filesDialog.show()
                    }

                    override fun onItemLongClick(file: File?, path: String?) {
                    }
                })

                setRefreshListener {
                    setVisibilityAnim(nothingText, isNoFile)
                }
            }

            tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    refreshType(tabLayout.selectedTabPosition)
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {
                }

                override fun onTabReselected(tab: TabLayout.Tab?) {
                }
            })

            actionBar.apply {
                pasteButton.setOnClickListener { _: View? ->
                    backgroundMap[backgroundType] = NULL
                    BackgroundManager.saveProperties(backgroundMap)
                    Toast.makeText(requireActivity(), getString(R.string.custom_background_reset, currentStatusName), Toast.LENGTH_SHORT).show()
                    refreshBackground()
                }

                returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
                addFileButton.setOnClickListener { openDocumentLauncher?.launch(arrayOf("image/*")) }
                refreshButton.setOnClickListener {
                    fileRecyclerView.listFileAt(backgroundPath())
                }
            }

            fileRecyclerView.lockAndListAt(backgroundPath(), backgroundPath())

            refreshType(tabLayout.selectedTabPosition)
        }

        startNewbieGuide()
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        binding.actionBar.apply {
            val fragmentActivity = requireActivity()
            TapTargetSequence(fragmentActivity)
                .targets(
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, pasteButton, getString(R.string.generic_reset), getString(R.string.newbie_guide_background_reset)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.custom_background_add), getString(R.string.newbie_guide_background_import)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_return), getString(R.string.newbie_guide_general_close)))
                .start()
        }
    }

    private fun initBackgroundMap() {
        BackgroundManager.apply {
            backgroundMap[BackgroundType.MAIN_MENU] = properties[BackgroundType.MAIN_MENU.name] as String? ?: NULL
            backgroundMap[BackgroundType.CUSTOM_CONTROLS] = properties[BackgroundType.CUSTOM_CONTROLS.name] as String? ?: NULL
            backgroundMap[BackgroundType.IN_GAME] = properties[BackgroundType.IN_GAME.name] as String? ?: NULL
        }
    }

    private fun backgroundPath(): File {
        val dirBackground = PathManager.DIR_BACKGROUND
        if (!dirBackground.exists()) mkdirs(dirBackground)
        return dirBackground
    }

    private fun refreshBackground() {
        if (backgroundType == BackgroundType.MAIN_MENU) EventBus.getDefault().post(
            MainBackgroundChangeEvent())
        refreshBackgroundPreview()
    }

    private val currentStatusName: String
        get() = when (this.backgroundType) {
            BackgroundType.MAIN_MENU -> getString(R.string.custom_background_main_menu)
            BackgroundType.CUSTOM_CONTROLS -> getString(R.string.option_edit_controls)
            BackgroundType.IN_GAME -> getString(R.string.custom_background_in_game)
        }

    private fun refreshType(index: Int) {
        when (index) {
            1 -> this.backgroundType = BackgroundType.CUSTOM_CONTROLS
            2 -> this.backgroundType = BackgroundType.IN_GAME
            0 -> this.backgroundType = BackgroundType.MAIN_MENU
            else -> this.backgroundType = BackgroundType.MAIN_MENU
        }

        refreshBackgroundPreview()
    }

    private fun refreshBackgroundPreview() {
        binding.preview.let {
            BackgroundManager.getBackgroundImage(backgroundType)?.apply {
                Glide.with(requireActivity())
                    .load(this)
                    .fitCenter()
                    .into(DrawableImageViewTarget(it))
                return
            }

            it.setImageDrawable(null)
        }
    }

    private fun initViews() {
        binding.fileRecyclerView.setFileIcon(FileIcon.FILE)

        binding.actionBar.apply {
            createFolderButton.visibility = View.GONE
            searchButton.visibility = View.GONE

            pasteButton.setContentDescription(getString(R.string.generic_reset))
            addFileButton.setContentDescription(getString(R.string.custom_background_add))
            pasteButton.setImageDrawable(ContextCompat.getDrawable(requireActivity(), R.drawable.ic_reset))

            ZHTools.setTooltipText(
                returnButton,
                addFileButton,
                pasteButton,
                refreshButton
            )
        }
    }

    private fun bindTabs() {
        binding.tabLayout.apply {
            val mainMenu = newTab()
            val controls = newTab()
            val inGame = newTab()

            mainMenu.setText(resources.getText(R.string.custom_background_main_menu))
            controls.setText(resources.getText(R.string.option_edit_controls))
            inGame.setText(resources.getText(R.string.custom_background_in_game))

            addTab(mainMenu)
            addTab(controls)
            addTab(inGame)

            selectTab(mainMenu)
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(backgroundLayout, TurtleTransitions.enter()))
                .apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.enter()))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(backgroundLayout, TurtleTransitions.exit()))
                .apply(AnimPlayer.Entry(operateLayout, TurtleTransitions.exit()))
        }
    }
}
