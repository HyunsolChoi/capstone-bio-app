// app/src/main/java/com/jjangdol/biorhythm/ui/admin/NotificationAdapter.kt
package com.jjangdol.biorhythm.ui.admin

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jjangdol.biorhythm.databinding.ItemNotificationBinding
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import java.text.SimpleDateFormat
import java.util.*

class NotificationAdapter(
    private val onItemClick: (Notification) -> Unit,
    private val onEditClick: (Notification) -> Unit,
    private val onDeleteClick: (Notification) -> Unit,
    private val onToggleStatus: (Notification) -> Unit
) : ListAdapter<Notification, NotificationAdapter.NotificationViewHolder>(NotificationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NotificationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NotificationViewHolder(
        private val binding: ItemNotificationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(notification: Notification) {
            binding.apply {
                tvNotificationTitle.text = notification.title
                tvNotificationContent.text = notification.content

                // 우선순위 표시
                tvPriority.text = notification.priority.displayName
                val priorityColor = Color.parseColor(notification.priority.colorRes)
                tvPriority.setTextColor(priorityColor)
                chipPriority.setBackgroundColor(priorityColor)

                // 날짜 표시
                val dateFormatter = SimpleDateFormat("YYYY-MM-dd HH:mm", Locale.getDefault())
                tvCreatedDate.text = notification.createdAt?.toDate()?.let { dateFormatter.format(it) } ?: ""

                // 상태 표시
                switchActive.isChecked = notification.active
                tvStatus.text = if (notification.active) "활성" else "비활성"

                // 비활성 상태일 때 투명도 조정
                root.alpha = if (notification.active) 1.0f else 0.6f

                // 클릭 리스너들
                root.setOnClickListener { onItemClick(notification) }
                btnEdit.setOnClickListener { onEditClick(notification) }
                btnDelete.setOnClickListener { onDeleteClick(notification) }
                switchActive.setOnCheckedChangeListener { _, _ -> onToggleStatus(notification) }
            }
        }
    }

    class NotificationDiffCallback : DiffUtil.ItemCallback<Notification>() {
        override fun areItemsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem == newItem
        }
    }
}