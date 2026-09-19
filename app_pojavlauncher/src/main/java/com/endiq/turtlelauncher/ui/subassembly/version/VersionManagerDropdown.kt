package com.endiq.turtlelauncher.ui.subassembly.version

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import androidx.fragment.app.Fragment
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.PopupVersionManagerBinding
import com.endiq.turtlelauncher.feature.version.NoVersionException
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.fragment.FilesFragment
import com.endiq.turtlelauncher.ui.fragment.ModsFragment
import com.endiq.turtlelauncher.ui.fragment.VersionConfigFragment
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileDeletionHandler
import net.kdt.pojavlaunch.Tools
import java.io.File

class VersionManagerDropdown(private val parentFragment: Fragment) {

    private val popupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    fun show(anchorView: View) {
        if (!parentFragment.isAdded || parentFragment.activity == null) return
        val context = parentFragment.requireActivity()

        val version: Version = VersionsManager.getCurrentVersion() ?: run {
            Tools.showError(context, context.getString(R.string.version_manager_no_installed_version), NoVersionException("There is no installed version"))
            return
        }
        val gameDirPath = version.getGameDir()

        val binding = PopupVersionManagerBinding.inflate(LayoutInflater.from(context)).apply {
            val onClickListener = View.OnClickListener { v ->
                when (v) {
                    shortcutsMods -> {
                        val bundle = Bundle()
                        bundle.putString(ModsFragment.BUNDLE_ROOT_PATH, File(gameDirPath, "/mods").mustExists().absolutePath)
                        ZHTools.swapFragmentWithAnim(parentFragment, ModsFragment::class.java, ModsFragment.TAG, bundle)
                    }
                    gamePath -> swapFilesFragment(gameDirPath, gameDirPath)
                    resourcePath -> swapFilesFragment(gameDirPath, File(gameDirPath, "/resourcepacks"))
                    worldPath -> swapFilesFragment(gameDirPath, File(gameDirPath, "/saves"))
                    shaderPath -> swapFilesFragment(gameDirPath, File(gameDirPath, "/shaderpacks"))
                    screenshotPath -> swapFilesFragment(gameDirPath, File(gameDirPath, "/screenshots"))
                    logsPath -> swapFilesFragment(gameDirPath, File(gameDirPath, "/logs"))
                    crashReportPath -> swapFilesFragment(gameDirPath, File(gameDirPath, "/crash-reports"))

                    versionSettings -> ZHTools.swapFragmentWithAnim(parentFragment, VersionConfigFragment::class.java, VersionConfigFragment.TAG, null)
                    versionRename -> VersionsManager.openRenameDialog(context, version)
                    versionCopy -> VersionsManager.openCopyDialog(context, version)
                    versionDelete -> {
                        TipDialog.Builder(context)
                            .setTitle(R.string.generic_warning)
                            .setMessage(context.getString(R.string.version_manager_delete_tip, version.getVersionName()))
                            .setWarning()
                            .setConfirmClickListener {
                                FileDeletionHandler(
                                    context,
                                    listOf(version.getVersionPath()),
                                    Task.runTask {
                                        VersionsManager.refresh("VersionManagerDropdown:versionDelete")
                                    }
                                ).start()
                            }
                            .showDialog()
                    }
                    else -> {}
                }
                popupWindow.dismiss()
            }
            shortcutsMods.setOnClickListener(onClickListener)
            gamePath.setOnClickListener(onClickListener)
            resourcePath.setOnClickListener(onClickListener)
            worldPath.setOnClickListener(onClickListener)
            shaderPath.setOnClickListener(onClickListener)
            screenshotPath.setOnClickListener(onClickListener)
            logsPath.setOnClickListener(onClickListener)
            crashReportPath.setOnClickListener(onClickListener)
            versionSettings.setOnClickListener(onClickListener)
            versionRename.setOnClickListener(onClickListener)
            versionCopy.setOnClickListener(onClickListener)
            versionDelete.setOnClickListener(onClickListener)
        }

        popupWindow.apply {
            binding.root.measure(0, 0)
            contentView = binding.root

            // Same overflow guard as VersionAdapter.showPopupWindow: cap to whichever of
            // "space below anchor" / "space above anchor" is bigger, ScrollView in
            // popup_version_manager.xml handles anything that still doesn't fit.
            val anchorLocation = IntArray(2)
            anchorView.getLocationOnScreen(anchorLocation)
            val screenHeight = context.resources.displayMetrics.heightPixels
            val spaceBelow = screenHeight - (anchorLocation[1] + anchorView.height)
            val spaceAbove = anchorLocation[1]
            val maxAvailable = maxOf(spaceBelow, spaceAbove, context.resources.getDimensionPixelSize(R.dimen._120sdp))

            width = binding.root.measuredWidth
            height = minOf(binding.root.measuredHeight, maxAvailable)
            // manager_profile_button sits at the right edge of the right-hand play panel, so
            // right-align the dropdown to the button instead of growing off the screen edge.
            val xOff = anchorView.width - binding.root.measuredWidth
            showAsDropDown(anchorView, xOff, 0)
        }
    }

    private fun File.mustExists(): File {
        if (!exists()) mkdirs()
        return this
    }

    private fun swapFilesFragment(lockPath: File, listPath: File) {
        val bundle = Bundle()
        bundle.putString(FilesFragment.BUNDLE_LOCK_PATH, lockPath.mustExists().absolutePath)
        bundle.putString(FilesFragment.BUNDLE_LIST_PATH, listPath.mustExists().absolutePath)
        bundle.putBoolean(FilesFragment.BUNDLE_QUICK_ACCESS_PATHS, false)
        ZHTools.swapFragmentWithAnim(parentFragment, FilesFragment::class.java, FilesFragment.TAG, bundle)
    }
}
