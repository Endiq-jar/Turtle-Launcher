package com.endiq.turtlelauncher.ui.fragment
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.endiq.anim.AnimPlayer
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.FragmentCustomMouseBinding
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.FilesDialog
import com.endiq.turtlelauncher.ui.dialog.FilesDialog.FilesButton
import com.endiq.turtlelauncher.ui.dialog.PixelEditorDialog
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileIcon
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileItemBean
import com.endiq.turtlelauncher.ui.subassembly.filelist.FileRecyclerViewCreator
import com.endiq.turtlelauncher.utils.NewbieGuideUtils
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.mkdirs
import com.endiq.turtlelauncher.feature.turtle.cursor.CustomCursorLoader
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomMouseFragment : FragmentWithAnim(R.layout.fragment_custom_mouse) {
    companion object {
        const val TAG: String = "CustomMouseFragment"
    }

    private lateinit var binding: FragmentCustomMouseBinding
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private var fileRecyclerViewCreator: FileRecyclerViewCreator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult<Array<String>, Uri>(ActivityResultContracts.OpenDocument()) { result: Uri? ->
            result?.let { uri ->
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                // Snapshot the context up front: requireActivity() from the worker
                // thread would crash if the user navigates away mid-copy.
                val appContext = requireContext().applicationContext
                val destPath = mousePath().absolutePath
                Task.runTask {
                    FileTools.copyFileInBackground(appContext, uri, destPath)
                }.ended(TaskExecutors.getAndroidUI()) {
                    if (!isAdded) return@ended
                    Toast.makeText(requireActivity(), getString(R.string.file_added), Toast.LENGTH_SHORT).show()
                    loadData()
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
        binding = FragmentCustomMouseBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews()

        binding.actionBar.apply {
            returnButton.setOnClickListener { ZHTools.onBackPressed(requireActivity()) }
            // TurtleLauncher: broadened from "image/*" so .cur/.ani cursor files show up too -
            // neither has a standard registered MIME type, so most document providers would
            // otherwise filter them out of the picker entirely. CustomCursorLoader validates
            // (and safely falls back to the default pointer for) whatever actually gets picked.
            addFileButton.setOnClickListener { openDocumentLauncher.launch(arrayOf("*/*")) }
            refreshButton.setOnClickListener { loadData() }
        }

        binding.drawCursorButton.setOnClickListener { openPixelEditor() }

        loadData()

        startNewbieGuide()
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        val fragmentActivity = requireActivity()
        binding.actionBar.apply {
            TapTargetSequence(fragmentActivity)
                .targets(
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.custom_mouse_add), getString(R.string.newbie_guide_mouse_import)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_close), getString(R.string.newbie_guide_general_close)))
                .start()
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadData() {
        val fileItemBeans = FileRecyclerViewCreator.loadItemBeansFromPath(
            requireActivity(),
            mousePath(),
            FileIcon.FILE,
            showFile = true,
            showFolder = false
        )
        fileItemBeans.add(0, FileItemBean(
            getString(R.string.custom_mouse_default),
            ContextCompat.getDrawable(requireActivity(), R.drawable.ic_mouse_pointer)
        ))
        TaskExecutors.runInUIThread {
            fileRecyclerViewCreator?.loadData(fileItemBeans)
            // Show the currently selected mouse by default.
            refreshIcon()
        }
    }

    private fun mousePath(): File {
        val path = File(PathManager.DIR_CUSTOM_MOUSE)
        if (!path.exists()) mkdirs(path)
        return path
    }

    /**
     * TurtleLauncher: opens the same pixel-art editor used for custom control-button icons,
     * pre-loading the currently active cursor if it happens to be a plain raster (.cur/.ani
     * frames aren't decodable by BitmapFactory, so those just open the editor blank - same
     * null-safe pattern EditControlPopup uses for its own "Draw" entry point).
     */
    private fun openPixelEditor() {
        val dialog = PixelEditorDialog(requireContext())
            .setOnSaveListener { bitmap -> saveCursorBitmap(bitmap) }
        ZHTools.getCustomMouse()?.let { file ->
            val existing = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (existing != null) dialog.withInitialBitmap(existing)
        }
        dialog.show()
    }

    /** Saves the drawn bitmap as a new virtual mouse file and immediately selects it. */
    private fun saveCursorBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        val fileName = "drawn_cursor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".png"
        val dest = File(mousePath(), fileName)
        try {
            FileOutputStream(dest).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: IOException) {
            Logging.e("CustomMouseFragment", "Failed to save the drawn cursor", e)
            Tools.showErrorRemote(e)
            return
        }
        AllSettings.customMouse.put(fileName).save()
        loadData()
        Toast.makeText(requireActivity(),
            StringUtils.insertSpace(getString(R.string.custom_mouse_added), fileName),
            Toast.LENGTH_SHORT).show()
    }

    private fun refreshIcon() {
        binding.mouseIcon.apply {
            ZHTools.getCustomMouse()?.let { file ->
                Glide.with(requireActivity())
                    .load(file)
                    .override(width, height)
                    .fitCenter()
                    .into(DrawableImageViewTarget(this))
                return@apply
            }
            setImageDrawable(ZHTools.customMouse(context))
        }
    }

    private fun initViews() {
        binding.actionBar.apply {
            addFileButton.setContentDescription(getString(R.string.custom_mouse_add))
            searchButton.visibility = View.GONE
            pasteButton.visibility = View.GONE
            createFolderButton.visibility = View.GONE

            ZHTools.setTooltipText(
                returnButton,
                addFileButton,
                refreshButton
            )
        }

        fileRecyclerViewCreator = FileRecyclerViewCreator(requireActivity(), binding.recyclerView, { position: Int, fileItemBean: FileItemBean ->
                val file = fileItemBean.file
                val fileName = file?.name
                val isDefaultMouse = position == 0

                val filesButton = FilesButton()
                filesButton.setButtonVisibility(false, false,
                    !isDefaultMouse, !isDefaultMouse, !isDefaultMouse, (isDefaultMouse || CustomCursorLoader.isSupportedCursorFile(file))) // the default virtual mouse cannot be shared, renamed or deleted

                // Selecting the default virtual mouse adds an extra hint.
                var message = getString(R.string.file_message)
                if (isDefaultMouse) message += """
     
     ${getString(R.string.custom_mouse_message_default)}
     """.trimIndent()
                filesButton.setMessageText(message)
                filesButton.setMoreButtonText(getString(R.string.generic_select))

                val filesDialog = FilesDialog(requireActivity(), filesButton, Task.runTask { loadData() }, mousePath(), file)
                filesDialog.setMoreButtonClick {
                    AllSettings.customMouse.put(fileName ?: "").save()
                    refreshIcon()
                    Toast.makeText(requireActivity(),
                        StringUtils.insertSpace(getString(R.string.custom_mouse_added), (fileName ?: getString(R.string.custom_mouse_default))),
                        Toast.LENGTH_SHORT).show()
                    filesDialog.dismiss()
                }
                filesDialog.show()
            },
            null
        )
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.mouseLayout, TurtleTransitions.enter()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.enter()))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.mouseLayout, TurtleTransitions.exit()))
            .apply(AnimPlayer.Entry(binding.operateLayout, TurtleTransitions.exit()))
    }
}
