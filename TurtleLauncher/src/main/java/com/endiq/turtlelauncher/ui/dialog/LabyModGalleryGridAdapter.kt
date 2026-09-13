package com.endiq.turtlelauncher.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.endiq.turtlelauncher.databinding.ItemSkinCapeGalleryBinding
import com.endiq.turtlelauncher.feature.skin.LabyModGalleryApi
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Grid of live laby.net skin library thumbnails (see [LabyModGalleryApi]) shown in
 * [SkinCapeDialog]. Unlike [SkinCapeGalleryAdapter] (local bitmaps, already decoded up front),
 * tiles here load their thumbnail over the network individually via Glide, since a gallery
 * page can be dozens of remote images - loading them all before showing anything would make
 * the grid feel like it hung.
 */
internal class LabyModGalleryGridAdapter(
    private val items: List<LabyModGalleryApi.GallerySkin>,
    private val onItemClick: (position: Int) -> Unit
) : RecyclerView.Adapter<LabyModGalleryGridAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSkinCapeGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val skin = items[position]
        holder.binding.label.text = skin.label
        Glide.with(holder.binding.image).load(LabyModGalleryApi.thumbnailUrl(skin.hash)).apply {
            if (!AllSettings.resourceImageCache.getValue()) diskCacheStrategy(DiskCacheStrategy.NONE)
        }.into(holder.binding.image)
        holder.binding.root.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemSkinCapeGalleryBinding) : RecyclerView.ViewHolder(binding.root)
}
