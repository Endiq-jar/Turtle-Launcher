package com.endiq.turtlelauncher.feature.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.download.utils.VersionSeriesUtils


/**
 * One card per Minecraft version series (e.g. "1.21", "26.2"), plus the two fixed
 * Beta/Alpha era cards. Tapping a card hands its full version list to
 * [com.endiq.turtlelauncher.ui.fragment.VersionSeriesDetailFragment].
 */
class SeriesCardAdapter(
    private val cards: List<CardEntry>,
    private val onCardClick: (CardEntry) -> Unit
) : RecyclerView.Adapter<SeriesCardAdapter.ViewHolder>() {

    /** A card is either a numbered series or one of the two fixed era buckets. */
    data class CardEntry(
        val label: String,
        val versionCount: Int,
        val iconRes: Int,
        val isLatest: Boolean,
        val versions: List<net.kdt.pojavlaunch.JMinecraftVersionList.Version>
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.series_icon)
        val label: android.widget.TextView = view.findViewById(R.id.series_label)
        val count: android.widget.TextView = view.findViewById(R.id.series_count)
        val latestBadge: android.widget.TextView = view.findViewById(R.id.series_latest_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_version_series_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        holder.icon.setImageResource(card.iconRes)
        holder.label.text = card.label
        holder.count.text = holder.itemView.resources.getQuantityString(
            R.plurals.version_series_count, card.versionCount, card.versionCount
        )
        holder.latestBadge.visibility = if (card.isLatest) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onCardClick(card) }
    }

    override fun getItemCount(): Int = cards.size
}
