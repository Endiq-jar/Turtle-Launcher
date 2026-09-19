package com.endiq.turtlelauncher.ui.subassembly.customprofilepath

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.RadioButton
import androidx.recyclerview.widget.RecyclerView
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.databinding.ItemProfilePathBinding
import net.kdt.pojavlaunch.databinding.ViewPathManagerBinding
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathManager
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathManager.setCurrentPathId
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings.Companion.launcherProfile
import com.endiq.turtlelauncher.ui.dialog.EditTextDialog
import com.endiq.turtlelauncher.ui.dialog.TipDialog
import com.endiq.turtlelauncher.ui.fragment.FilesFragment
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import com.endiq.turtlelauncher.utils.StoragePermissionsUtils
import com.endiq.turtlelauncher.utils.ZHTools

class ProfilePathAdapter(
    private val fragment: FragmentWithAnim,
    private val view: RecyclerView
) :
    RecyclerView.Adapter<ProfilePathAdapter.ViewHolder>() {
    private val mData: MutableList<ProfileItem> = ArrayList()
    private val radioButtonList: MutableList<RadioButton> = mutableListOf()
    // Without the storage permission, fall back to the default path.
    private var currentId: String? = if (StoragePermissionsUtils.checkPermissions()) launcherProfile.getValue() else "default"
    private val managerPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemProfilePathBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setView(mData[position], position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        radioButtonList.remove(holder.binding.radioButton)
    }

    override fun getItemCount(): Int = mData.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(data: MutableList<ProfileItem>) {
        this.mData.clear()
        this.mData.addAll(data)
        radioButtonList.apply {
            forEach { radioButton -> radioButton.isChecked = false }
            clear()
        }
        notifyDataSetChanged()
        view.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        ProfilePathManager.save(mData)
        ProfilePathManager.refreshPath()
        notifyDataSetChanged()
        view.scheduleLayoutAnimation()
    }

    fun closePopupWindow() {
        managerPopupWindow.dismiss()
    }

    private fun setPathId(id: String) {
        currentId = id
        setCurrentPathId(id)
        radioButtonList.forEach { radioButton -> radioButton.isChecked = radioButton.tag.toString() == id }
    }

    inner class ViewHolder(val binding: ItemProfilePathBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun setView(profileItem: ProfileItem, position: Int) {
            binding.apply {
                radioButtonList.add(
                    radioButton.apply {
                        tag = profileItem.id
                        isChecked = currentId == profileItem.id
                    }
                )
                title.text = profileItem.title
                path.text = profileItem.path
                path.isSelected = true

                val onClickListener = View.OnClickListener {
                    if (VersionsManager.canRefresh() && currentId != profileItem.id) {
                        StoragePermissionsUtils.checkPermissions(
                            activity = fragment.requireActivity(),
                            title = R.string.profiles_path_title,
                            permissionGranted = object : StoragePermissionsUtils.PermissionGranted {
                                override fun granted() {
                                    setPathId(profileItem.id)
                                }

                                override fun cancelled() {}
                            }
                        )
                    }
                }
                root.setOnClickListener(onClickListener)
                radioButton.setOnClickListener(onClickListener)

                operate.setOnClickListener {
                    showPopupWindow(root, profileItem.id == "default", profileItem, position)
                }
            }
        }

        private fun showPopupWindow(
            anchorView: View,
            isDefault: Boolean,
            profileItem: ProfileItem,
            itemIndex: Int
        ) {
            val context = anchorView.context

            val viewBinding = ViewPathManagerBinding.inflate(LayoutInflater.from(context)).apply {
                val onClickListener = View.OnClickListener { v ->
                    when (v) {
                        gotoView -> {
                            val bundle = Bundle()
                            bundle.putString(FilesFragment.BUNDLE_LOCK_PATH, Environment.getExternalStorageDirectory().absolutePath)
                            bundle.putString(FilesFragment.BUNDLE_LIST_PATH, profileItem.path)
                            ZHTools.swapFragmentWithAnim(
                                fragment,
                                FilesFragment::class.java, FilesFragment.TAG, bundle
                            )
                        }
                        rename -> {
                            EditTextDialog.Builder(context)
                                .setTitle(R.string.generic_rename)
                                .setEditText(profileItem.title)
                                .setAsRequired()
                                .setConfirmListener { editBox, _ ->
                                    val string = editBox.text.toString()

                                    mData[itemIndex].title = string
                                    refresh()
                                    true
                                }.showDialog()
                        }
                        delete -> {
                            TipDialog.Builder(context)
                                .setTitle(context.getString(R.string.profiles_path_delete_title))
                                .setMessage(R.string.profiles_path_delete_message)
                                .setCancelable(false)
                                .setConfirmClickListener {
                                    if (currentId == profileItem.id) {
                                        // If the removed path was the selected one, fall back to the default path.
                                        setPathId("default")
                                    }
                                    mData.removeAt(itemIndex)
                                    refresh()
                                }.showDialog()
                        }
                        else -> {}
                    }
                    managerPopupWindow.dismiss()
                }
                gotoView.setOnClickListener(onClickListener)
                rename.setOnClickListener(onClickListener)
                delete.setOnClickListener(onClickListener)
                if (isDefault) {
                    renameLayout.visibility = View.GONE
                    deleteLayout.visibility = View.GONE
                }
            }
            managerPopupWindow.apply {
                viewBinding.root.measure(0, 0)
                this.contentView = viewBinding.root
                this.width = viewBinding.root.measuredWidth
                this.height = viewBinding.root.measuredHeight
                showAsDropDown(anchorView, anchorView.measuredWidth, 0)
            }
        }
    }
}
