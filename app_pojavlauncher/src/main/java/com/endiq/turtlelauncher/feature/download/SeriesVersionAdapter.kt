package com.endiq.turtlelauncher.feature.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.JMinecraftVersionList

class SeriesVersionAdapter(
    private val rows: List<Row>,
    private val onVersionClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class VersionRow(val version: JMinecraftVersionList.Version, val typeBadge: String) : Row()
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_VERSION = 1
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: android.widget.TextView = view.findViewById(R.id.section_header_title)
    }

    class VersionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: android.widget.TextView = view.findViewById(R.id.version_row_name)
        val badge: android.widget.TextView = view.findViewById(R.id.version_row_badge)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.VersionRow -> TYPE_VERSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_version_section_header, parent, false))
        } else {
            VersionHolder(inflater.inflate(R.layout.item_version_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).title.text = row.title
            is Row.VersionRow -> {
                holder as VersionHolder
                holder.name.text = row.version.id
                holder.badge.text = row.typeBadge
                holder.itemView.setOnClickListener { onVersionClick(row.version.id) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size
}
