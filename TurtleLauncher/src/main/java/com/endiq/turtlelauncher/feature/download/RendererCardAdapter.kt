package com.endiq.turtlelauncher.feature.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.renderer.RendererCatalog

/**
 * One card per renderer for the Renderer Manager screen: name, stability badge,
 * a compatibility note (version range / missing-library warning / Vulkan requirement),
 * and a checkmark on whichever one is currently selected.
 */
class RendererCardAdapter(
    private val cards: List<CardEntry>,
    private val onCardClick: (CardEntry) -> Unit
) : RecyclerView.Adapter<RendererCardAdapter.ViewHolder>() {

    data class CardEntry(
        val uniqueIdentifier: String,
        val name: String,
        val badge: RendererCatalog.Badge,
        val compatNote: String?,
        val isSelected: Boolean,
        /** Non-null when this card represents a renderer that isn't installed yet - tapping
         *  it downloads+installs this asset instead of selecting a renderer. */
        val downloadAsset: com.endiq.turtlelauncher.feature.pluginupdate.PluginAssetInfo? = null
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: android.widget.TextView = view.findViewById(R.id.renderer_name)
        val badge: android.widget.TextView = view.findViewById(R.id.renderer_badge)
        val compatNote: android.widget.TextView = view.findViewById(R.id.renderer_compat_note)
        val check: android.widget.TextView = view.findViewById(R.id.renderer_selected_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_renderer_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        holder.name.text = card.name
        holder.badge.text = when (card.badge) {
            RendererCatalog.Badge.RECOMMENDED -> holder.itemView.context.getString(R.string.renderer_badge_recommended)
            RendererCatalog.Badge.STABLE -> holder.itemView.context.getString(R.string.renderer_badge_stable)
            RendererCatalog.Badge.EXPERIMENTAL -> holder.itemView.context.getString(R.string.renderer_badge_experimental)
        }
        holder.compatNote.text = card.compatNote
        holder.compatNote.visibility = if (card.compatNote.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.check.visibility = if (card.isSelected) View.VISIBLE else View.GONE
        holder.itemView.alpha = if (card.downloadAsset != null) 0.75f else 1.0f
        holder.itemView.setOnClickListener { onCardClick(card) }
    }

    override fun getItemCount(): Int = cards.size
}
