package com.endiq.turtlelauncher.feature.mod.modloader

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.endiq.anim.animations.Animations
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.ItemFileListViewBinding
import com.endiq.turtlelauncher.feature.download.item.VersionItem
import com.endiq.turtlelauncher.utils.anim.ViewAnimUtils
import net.kdt.pojavlaunch.modloaders.FabricVersion
import net.kdt.pojavlaunch.modloaders.OptiFineUtils.OptiFineVersion

class ModVersionListAdapter(
    private val iconDrawable: Int,
    private val mData: List<Any>
) :
    RecyclerView.Adapter<ModVersionListAdapter.ViewHolder>() {
    private var onItemClickListener: OnItemClickListener? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemFileListViewBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setView(mData[position])
    }

    override fun getItemCount(): Int {
        return mData.size
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }

    fun interface OnItemClickListener {
        /**
         * @return false when a task is running and this click must be blocked
         */
        fun onClick(version: Any): Boolean
    }

    inner class ViewHolder(private val binding: ItemFileListViewBinding) : RecyclerView.ViewHolder(binding.root) {
        private val context: Context = itemView.context

        init {
            binding.image.setImageResource(iconDrawable)
            binding.check.visibility = View.GONE
        }

        fun setView(version: Any) {
            when (version) {
                is OptiFineVersion -> binding.name.text = version.versionName
                is FabricVersion -> binding.name.text = version.version
                is VersionItem -> binding.name.text = version.title
                is String -> binding.name.text = version
            }
            itemView.setOnClickListener {
                if (onItemClickListener?.onClick(version) == false) {
                    ViewAnimUtils.setViewAnim(itemView, Animations.Shake)
                    Toast.makeText(context, context.getString(R.string.tasks_ongoing), Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
            }
        }
    }
}
