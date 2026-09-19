package com.endiq.turtlelauncher.ui.dialog

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.kdt.pojavlaunch.databinding.ItemSkinCapeGalleryBinding

/**
 * Horizontal gallery of skin/cape thumbnails shown in [SkinCapeDialog] - tap a tile to apply it
 * immediately. Backed entirely by local data (a bundled default +
 * [com.endiq.turtlelauncher.feature.skin.SkinCapeHistoryStore]'s applied-history), not a live
 * network browse.
 */
class SkinCapeGalleryAdapter(
    private val items: List<Pair<String, Bitmap?>>,
    private val onItemClick: (position: Int) -> Unit
) : RecyclerView.Adapter<SkinCapeGalleryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSkinCapeGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (label, bitmap) = items[position]
        holder.binding.image.setImageBitmap(bitmap)
        holder.binding.label.text = label
        holder.binding.root.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemSkinCapeGalleryBinding) : RecyclerView.ViewHolder(binding.root)
}
