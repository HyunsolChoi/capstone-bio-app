// app/src/main/java/com/jjangdol/biorhythm/ui/admin/AdminResultsAdapter.kt
package com.jjangdol.biorhythm.ui.admin

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.jjangdol.biorhythm.model.ChecklistResult

class AdminResultsAdapter(
    private val onItemClick: (ChecklistResult) -> Unit
) : ListAdapter<ChecklistResult, AdminResultsAdapter.ResultViewHolder>(ResultDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val textView = TextView(parent.context).apply {
            setPadding(32, 24, 32, 24)
            textSize = 16f
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        return ResultViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {

        fun bind(item: ChecklistResult) {
            textView.apply {
                //text = "${item.name} (${item.dept}) - ${item.finalSafetyScore}점"
                text = "${item.name} - ${item.finalSafetyScore}점"

                // 점수에 따른 색상 변경
                setTextColor(when {
                    item.finalSafetyScore < 50 -> android.graphics.Color.RED
                    item.finalSafetyScore < 70 -> android.graphics.Color.parseColor("#FF9800")
                    else -> android.graphics.Color.parseColor("#4CAF50")
                })

                setOnClickListener { onItemClick(item) }
            }
        }
    }

    class ResultDiffCallback : DiffUtil.ItemCallback<ChecklistResult>() {
        override fun areItemsTheSame(oldItem: ChecklistResult, newItem: ChecklistResult): Boolean {
            return oldItem.userId == newItem.userId && oldItem.date == newItem.date
        }

        override fun areContentsTheSame(oldItem: ChecklistResult, newItem: ChecklistResult): Boolean {
            return oldItem == newItem
        }
    }
}