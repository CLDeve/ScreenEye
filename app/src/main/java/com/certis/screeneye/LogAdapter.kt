package com.certis.screeneye

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.certis.screeneye.data.LogEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val items = mutableListOf<LogEvent>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun submit(events: List<LogEvent>) {
        items.clear()
        items.addAll(events)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            android.R.layout.simple_list_item_2,
            parent,
            false
        )
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val item = items[position]
        val time = dateFormat.format(Date(item.timestampMs))
        val message = buildString {
            append(item.type)
            if (!item.message.isNullOrBlank()) {
                append(" - ").append(item.message)
            }
            if (item.durationMs != null) {
                append(" (").append(item.durationMs).append("ms)")
            }
        }
        holder.title.text = time
        holder.subtitle.text = message
    }

    override fun getItemCount(): Int = items.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(android.R.id.text1)
        val subtitle: TextView = itemView.findViewById(android.R.id.text2)
    }
}
