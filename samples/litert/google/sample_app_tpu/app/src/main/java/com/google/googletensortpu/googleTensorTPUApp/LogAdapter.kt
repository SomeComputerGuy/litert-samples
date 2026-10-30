package com.google.googletensortpu.googleTensorTPUApp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.googletensortpu.googleTensorTPUApp.AnthropicServerService.LogEntry
import com.google.googletensortpu.googleTensorTPUApp.AnthropicServerService.LogKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows the API server's traffic log: server events, requests and responses. */
class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var entries: List<LogEntry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textLogTitle)
        val time: TextView = view.findViewById(R.id.textLogTime)
        val body: TextView = view.findViewById(R.id.textLogBody)
    }

    fun submit(newEntries: List<LogEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false))

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.title
        holder.title.setTextColor(
            when (entry.kind) {
                LogKind.REQUEST -> 0xFF60A5FA.toInt()  // blue: incoming
                LogKind.RESPONSE -> 0xFF34D399.toInt() // green: outgoing
                LogKind.ERROR -> 0xFFF87171.toInt()
                LogKind.INFO -> 0xFF9CA3AF.toInt()
            }
        )
        holder.time.text = timeFormat.format(Date(entry.timeMillis))
        holder.body.text = entry.body
        holder.body.visibility = if (entry.body.isEmpty()) View.GONE else View.VISIBLE
    }
}
