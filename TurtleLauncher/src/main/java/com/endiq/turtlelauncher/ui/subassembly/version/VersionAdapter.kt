package com.endiq.turtlelauncher.ui.subassembly.version

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.ItemVersionBinding
import com.endiq.turtlelauncher.databinding.ViewVersionManagerBinding
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathManager
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.feature.version.utils.VersionIconUtils
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.feature.version.ModpackExporter
import java.io.File
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.fragment.FilesFragment
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.file.FileDeletionHandler
import net.endiq.launcher.Tools

class VersionAdapter(
    private val parentFragment: Fragment,
    private val listener: OnVersionItemClickListener
) : RecyclerView.Adapter<VersionAdapter.ViewHolder>() {
    private val versions: MutableList<Version> = ArrayList()
    // List of every RadioButton, recording the version path each one represents.
    private val radioButtonList: MutableList<RadioButton> = mutableListOf()
    private var currentVersion: String? = null
    private var managerPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshVersions(versions: List<Version>): Int {
        this.versions.clear()
        this.versions.addAll(versions)
        this.radioButtonList.apply {
            forEach { radioButton -> radioButton.isChecked = false }
            clear()
        }
        currentVersion = VersionsManager.getCurrentVersion()?.getVersionPath()?.absolutePath
        // Find the index of the current version.
        val currentIndex = versions.indexOfFirst { it.getVersionPath().absolutePath == currentVersion }
        notifyDataSetChanged()

        return currentIndex
    }

    fun closePopupWindow() {
        managerPopupWindow.dismiss()
    }

    private fun setCurrentVersion(context: Context, version: Version) {
        if (version.isValid()) {
            VersionsManager.saveCurrentVersion(version.getVersionName())
            currentVersion = version.getVersionPath().absolutePath
        } else {
            // An invalid version cannot be selected; tapping it offers to delete it.
            deleteVersion(version, context.getString(R.string.version_manager_delete_tip_invalid))
        }
        radioButtonList.forEach { radioButton -> radioButton.isChecked = radioButton.tag.toString() == currentVersion }
    }

    // Warn the user before deleting a version; for an invalid version the default tap
    // action is deletion.
    private fun deleteVersion(version: Version, deleteMessage: String) {
        // FIX: Guard against IllegalStateException when fragment is detached during deletion
        if (!parentFragment.isAdded || parentFragment.activity == null) return
        val context = parentFragment.requireActivity()

        TipDialog.Builder(context)
            .setTitle(context.getString(R.string.version_manager_delete))
            .setMessage(deleteMessage)
            .setWarning()
            .setCancelable(false)
            .setConfirmClickListener {
                FileDeletionHandler(
                    context,
                    listOf(version.getVersionPath()),
                    Task.runTask {
                        // FIX: check fragment still attached before refreshing
                        try {
                            VersionsManager.refresh("VersionAdapter:deleteVersion")
                        } catch (e: Exception) {
                            com.endiq.turtlelauncher.feature.log.Logging.e("VersionAdapter", "Refresh after delete failed", e)
                        }
                    }
                ).start()
            }.showDialog()
    }

    private fun exportModpack(context: Context, version: Version) {
        Task.runTask {
            ModpackExporter.export(version)
        }.ended(TaskExecutors.getAndroidUI()) { outputFile ->
            if (outputFile != null) {
                TipDialog.Builder(context)
                    .setTitle(R.string.version_manager_export_modpack)
                    .setMessage(context.getString(R.string.modpack_export_success, outputFile.absolutePath))
                    .setShowCancel(false).setConfirm(R.string.generic_ok).showDialog()
            }
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                TipDialog.Builder(context)
                    .setTitle(R.string.version_manager_export_modpack)
                    .setMessage(context.getString(R.string.modpack_export_failure, e.message ?: e.javaClass.simpleName))
                    .setWarning().setShowCancel(false).setConfirm(R.string.generic_ok).showDialog()
            }
        }.execute()
    }

    private fun exportMrpack(context: Context, version: Version) {
        Task.runTask {
            ModpackExporter.exportMrpack(version)
        }.ended(TaskExecutors.getAndroidUI()) { outputFile ->
            if (outputFile != null) {
                TipDialog.Builder(context)
                    .setTitle(R.string.version_manager_export_mrpack)
                    .setMessage(context.getString(R.string.mrpack_export_success, outputFile.absolutePath))
                    .setShowCancel(false).setConfirm(R.string.generic_ok).showDialog()
            }
        }.onThrowable { e ->
            TaskExecutors.runInUIThread {
                TipDialog.Builder(context)
                    .setTitle(R.string.version_manager_export_mrpack)
                    .setMessage(context.getString(R.string.mrpack_export_failure, e.message ?: e.javaClass.simpleName))
                    .setWarning().setShowCancel(false).setConfirm(R.string.generic_ok).showDialog()
            }
        }.execute()
    }

    private fun showDepGraph(context: Context, version: Version) {
        val dialog = android.app.ProgressDialog(context).apply {
            setMessage(context.getString(R.string.dep_graph_title) + "…")
            setCancelable(false)
            show()
        }
        com.endiq.turtlelauncher.feature.mod.parser.ModParser.checkAllMods(
            version,
            object : com.endiq.turtlelauncher.feature.mod.parser.ModParserListener {
                override fun onProgress(recentlyParsedModInfo: com.endiq.turtlelauncher.feature.mod.parser.ModInfo, totalFileCount: Int) {}
                override fun onParseEnded(modList: List<com.endiq.turtlelauncher.feature.mod.parser.ModInfo>) {
                    TaskExecutors.runInUIThread {
                        dialog.dismiss()
                        val list = modList
                        val message = if (list.isEmpty()) {
                            context.getString(R.string.dep_graph_empty)
                        } else {
                            buildString {
                                list.sortedBy { it.name }.forEach { mod ->
                                    append("▸ ${mod.name} (${mod.version})")
                                    if (mod.dependencies.isNotEmpty()) {
                                        mod.dependencies.forEach { (depId, depVer) ->
                                            append("\n    └ $depId")
                                            if (depVer.isNotBlank()) append(" $depVer")
                                        }
                                    }
                                    append("\n\n")
                                }
                            }.trim()
                        }
                        TipDialog.Builder(context)
                            .setTitle(R.string.dep_graph_title)
                            .setMessage(message)
                            .setCenterMessage(false)
                            .setSelectable(true)
                            .setShowCancel(false)
                            .setConfirm(R.string.generic_ok)
                            .showDialog()
                    }
                }
            }
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemVersionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(versions[position])
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        radioButtonList.remove(holder.binding.radioButton)
    }

    override fun getItemCount(): Int = versions.size

    inner class ViewHolder(val binding: ItemVersionBinding) : RecyclerView.ViewHolder(binding.root) {
        private val mContext = binding.root.context

        private fun String.addInfoIfNotBlank(setRed: Boolean = false) {
            takeIf { it.isNotBlank() }?.let { string ->
                binding.versionInfoLayout.addView(getInfoTextView(string, setRed))
            }
        }

        fun bind(version: Version) {
            binding.apply {
                versionInfoLayout.removeAllViews()
                versionName.isSelected = true

                val versionPath = version.getVersionPath().absolutePath
                radioButtonList.add(
                    radioButton.apply {
                        tag = versionPath
                        isChecked = currentVersion == versionPath
                    }
                )

                versionName.text = version.getVersionName()

                if (!version.isValid()) {
                    mContext.getString(R.string.version_manager_invalid).addInfoIfNotBlank(true)
                }

                if (version.getVersionConfig().isIsolation()) {
                    mContext.getString(R.string.pedit_isolation_enabled).addInfoIfNotBlank()
                }

                version.getVersionInfo()?.let { versionInfo ->
                    versionInfoLayout.addView(getInfoTextView(versionInfo.minecraftVersion))
                    versionInfo.loaderInfo?.forEach { loaderInfo ->
                        loaderInfo.name.addInfoIfNotBlank()
                        loaderInfo.version.addInfoIfNotBlank()
                    }
                }

                favorite.setOnClickListener { _ ->
                    listener.showFavoritesDialog(version.getVersionName())
                }
                favorite.setImageDrawable(
                    ContextCompat.getDrawable(mContext,
                        if (listener.isVersionFavorited(version.getVersionName())) R.drawable.ic_favorite
                        else R.drawable.ic_favorite_border
                    )
                )

                operate.setOnClickListener { _ ->
                    showPopupWindow(operate, version)
                }

                VersionIconUtils(version).start(versionIcon)

                val onClickListener = View.OnClickListener { _ ->
                    setCurrentVersion(mContext, version)
                }
                radioButton.setOnClickListener(onClickListener)
                root.setOnClickListener(onClickListener)
            }
        }

        private fun getInfoTextView(string: String, setRed: Boolean = false): TextView {
            val textView = TextView(mContext)
            textView.text = string
            val layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, 0, Tools.dpToPx(8f).toInt(), 0)
            textView.layoutParams = layoutParams
            if (setRed) textView.setTextColor(Color.RED)
            return textView
        }

        private fun showPopupWindow(
            anchorView: View,
            version: Version
        ) {
            // FIX: guard against fragment detachment
            if (!parentFragment.isAdded || parentFragment.activity == null) return
            val context = parentFragment.requireActivity()

            val viewBinding = ViewVersionManagerBinding.inflate(LayoutInflater.from(context)).apply {
                val onClickListener = View.OnClickListener { v ->
                    when (v) {
                        gotoView -> swapPath(version.getVersionPath().absolutePath)
                        gamePath -> swapPath(version.getGameDir().absolutePath)
                        rename -> VersionsManager.openRenameDialog(context, version)
                        copy -> VersionsManager.openCopyDialog(context, version)
                        exportModpack -> exportModpack(context, version)
                        exportMrpack -> exportMrpack(context, version)
                        depGraph -> showDepGraph(context, version)
                        delete -> deleteVersion(version, context.getString(R.string.version_manager_delete_tip, version.getVersionName()))
                        else -> {}
                    }
                    managerPopupWindow.dismiss()
                }
                gotoView.setOnClickListener(onClickListener)
                gamePath.setOnClickListener(onClickListener)
                rename.setOnClickListener(onClickListener)
                copy.setOnClickListener(onClickListener)
                exportModpack.setOnClickListener(onClickListener)
                exportMrpack.setOnClickListener(onClickListener)
                depGraph.setOnClickListener(onClickListener)
                delete.setOnClickListener(onClickListener)
            }
            managerPopupWindow.apply {
                viewBinding.root.measure(0, 0)
                this.contentView = viewBinding.root

                val anchorLocation = IntArray(2)
                anchorView.getLocationOnScreen(anchorLocation)
                val screenHeight = context.resources.displayMetrics.heightPixels
                val spaceBelow = screenHeight - (anchorLocation[1] + anchorView.height)
                val spaceAbove = anchorLocation[1]
                val maxAvailable = maxOf(spaceBelow, spaceAbove, context.resources.getDimensionPixelSize(R.dimen._120sdp))

                this.width = viewBinding.root.measuredWidth
                this.height = minOf(viewBinding.root.measuredHeight, maxAvailable)
                showAsDropDown(anchorView, anchorView.measuredWidth, 0)
            }
        }

        private fun swapPath(path: String) {
            val bundle = Bundle()
            bundle.putString(FilesFragment.BUNDLE_LOCK_PATH, ProfilePathManager.getCurrentPath())
            bundle.putString(FilesFragment.BUNDLE_LIST_PATH, path)
            bundle.putBoolean(FilesFragment.BUNDLE_QUICK_ACCESS_PATHS, false)
            ZHTools.swapFragmentWithAnim(
                parentFragment,
                FilesFragment::class.java, FilesFragment.TAG, bundle
            )
        }
    }

    interface OnVersionItemClickListener {
        /**
         * The user tapped "favorite": check the state and show the favorites dialog.
         */
        fun showFavoritesDialog(versionName: String)

        /**
         * Check whether the current version is favorited.
         */
        fun isVersionFavorited(versionName: String): Boolean
    }
}
