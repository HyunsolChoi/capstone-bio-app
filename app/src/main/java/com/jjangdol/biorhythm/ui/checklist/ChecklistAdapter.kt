package com.jjangdol.biorhythm.ui.checklist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.jjangdol.biorhythm.databinding.ItemChecklistBinding
import com.jjangdol.biorhythm.model.ChecklistItem

class ChecklistAdapter(
    private var items: List<ChecklistItem>,
    private val onAnswerChanged: (pos: Int, selectedOption: Int) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.VH>() {

    inner class VH(private val b: ItemChecklistBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: ChecklistItem, isLast: Boolean) {
            b.tvQuestion.text = item.question

            // 모든 버튼 모아 배열로 관리
            val buttons = listOf(
                b.btnOption1,
                b.btnOption2,
                b.btnOption3,
                b.btnOption4,
                b.btnOption5
            )

            // 표시할 개수 (options가 null이면 2개만)
            val optionCount = item.options?.size ?: 2

            // 버튼 표시 제어
            buttons.forEachIndexed { index, btn ->
                if (index < optionCount) {
                    btn.visibility = View.VISIBLE
                    // Firestore에서 정의된 options 텍스트 사용
                    btn.text = item.options?.getOrNull(index) ?: (index + 1).toString()
                } else {
                    btn.visibility = View.GONE
                }

                // 기존 리스너 제거
                btn.setOnCheckedChangeListener(null)
                // 체크 상태 반영
                btn.isChecked = item.selectedOption == (index + 1)

                // 새 리스너 등록
                btn.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && adapterPosition != RecyclerView.NO_POSITION) {
                        onAnswerChanged(adapterPosition, index + 1)
                        notifyItemChanged(adapterPosition)
                    }
                }
            }

            // 구분선 처리
            b.viewDivider.visibility = if (isLast) View.GONE else View.VISIBLE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChecklistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val isLast = position == items.lastIndex
        holder.bind(items[position], isLast)
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<ChecklistItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].id == newItems[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newItems[newItemPosition]
            }
        }

        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }
}
