package com.jjangdol.biorhythm.ui.admin

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.ItemAdminChecklistBinding
import com.jjangdol.biorhythm.model.ChecklistConfig

class AdminChecklistAdapter(
    private val onWeightChanged: (Int, Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onEdit: (Int, String) -> Unit
) : ListAdapter<ChecklistConfig, AdminChecklistAdapter.ViewHolder>(DiffCallback()) {
    private val db = Firebase.firestore

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAdminChecklistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(
        private val binding: ItemAdminChecklistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isEditMode = false
        private var originalQuestion = ""

        fun bind(item: ChecklistConfig, position: Int) {
            with(binding) {

                // 문항 번호
                tvQuestionNumber.text = (position + 1).toString()

                // 문항 내용
                etQuestion.setText(item.question)
                originalQuestion = item.question

                // 문항 가중치 (EditText 기반)
                etWeight.setText(item.weight.toString())

                // 문항 가중치 값 변경 감지
                etWeight.addTextChangedListener {
                    val value = it.toString().toIntOrNull() ?: 0
                    val clamped = value.coerceIn(0, 100)
                    if (value != clamped) {
                        etWeight.setText(clamped.toString())
                        etWeight.setSelection(etWeight.text?.length ?: 0)
                        Toast.makeText(binding.root.context, "가중치는 0~100 사이여야 합니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        onWeightChanged(position, clamped)
                    }
                }

                // 기존 선택지 표시
                layoutOptionsContainer.removeAllViews()
                val options = item.options ?: listOf("보기 1", "보기 2")
                val optionWeights = item.optionWeights ?: List(options.size) { 0 }

                for (i in options.indices) {
                    addOptionView(i + 1, options[i], optionWeights[i])
                }

                // 선택지 추가 버튼
                btnAddOption.setOnClickListener {
                    if (!isEditMode) enterEditMode()
                    val count = layoutOptionsContainer.childCount
                    if (count < 5) {
                        addOptionView(count + 1, "", 0)
                    } else {
                        showToast("최대 5개까지 추가 가능합니다.")
                    }
                }

                // 선택지 삭제 버튼
                btnRemoveOption.setOnClickListener {
                    if (!isEditMode) enterEditMode()
                    val count = layoutOptionsContainer.childCount
                    if (count > 2) {
                        layoutOptionsContainer.removeViewAt(count - 1)
                    } else {
                        showToast("선택지는 최소 2개 이상 필요합니다.")
                    }
                }

                // 수정 버튼
                btnEdit.setOnClickListener {
                    if (!isEditMode) enterEditMode()
                }

                // 삭제 버튼
                btnDelete.setOnClickListener {
                    onDelete(position)
                }

                // 수정 취소 버튼
                btnCancel.setOnClickListener {
                    exitEditMode()
                    etQuestion.setText(originalQuestion)
                }

                // 수정 저장 버튼
                btnSaveEdit.setOnClickListener {
                    saveEditedItem()
                }
            }
        }

        /** 수정된 문항 및 보기 가중치를 검증 후 Firestore에 반영 */
        private fun saveEditedItem() {
            val newQuestion = binding.etQuestion.text.toString().trim()
            val totalWeight = getTotalOptionWeight()

            if (totalWeight > 100) {
                showToast("선택지 가중치의 총합이 100을 초과했습니다. (현재: $totalWeight)")
                return
            }

            if (newQuestion.isEmpty()) {
                showToast("문항 내용을 입력해주세요.")
                return
            }

            val options = mutableListOf<String>()
            val optionWeights = mutableListOf<Int>()

            for (i in 0 until binding.layoutOptionsContainer.childCount) {
                val optionView = binding.layoutOptionsContainer.getChildAt(i)
                val etOption = optionView.findViewById<TextInputEditText>(R.id.etOptionText)
                val etOptionWeight = optionView.findViewById<TextInputEditText>(R.id.etOptionWeight)

                val text = etOption.text.toString().trim()
                if (text.isNotEmpty()) {
                    val weight = etOptionWeight.text.toString().toIntOrNull() ?: 0
                    optionWeights.add(weight.coerceIn(0, 100))
                }
            }

            val updatedItem = getItem(adapterPosition).copy(
                question = newQuestion,
                options = options,
                optionWeights = optionWeights
            )

            val currentList = currentList.toMutableList()
            currentList[adapterPosition] = updatedItem
            submitList(currentList)

            updateFirestoreChecklistItem(
                id = updatedItem.id,
                question = newQuestion,
                options = options,
                optionWeights = optionWeights
            )

            exitEditMode()
            showToast("수정이 저장되었습니다.")
        }

        private fun updateFirestoreChecklistItem(
            id: String,
            question: String,
            options: List<String>,
            optionWeights: List<Int>
        ) {
            val updateMap = mapOf(
                "question" to question,
                "options" to options,
                "optionWeights" to optionWeights
            )

            db.collection("checklist").document(id)
                .update(updateMap)
                .addOnSuccessListener {
                    Log.d("Firestore", "문항 수정 성공: $id")
                }
                .addOnFailureListener { e ->
                    Log.e("Firestore", "문항 수정 실패: ${e.message}", e)
                    showToast("문항 수정 중 오류가 발생했습니다.")
                }
        }



        /** 선택지 하나 추가 */
        private fun addOptionView(index: Int, text: String = "", weight: Int = 0) {
            val inflater = LayoutInflater.from(binding.root.context)
            val optionView = inflater.inflate(R.layout.item_option_edit, binding.layoutOptionsContainer, false)

            val tvLabel = optionView.findViewById<TextView>(R.id.tvOptionLabel)
            val etText = optionView.findViewById<TextInputEditText>(R.id.etOptionText)
            val etWeight = optionView.findViewById<TextInputEditText>(R.id.etOptionWeight)

            tvLabel.text = "선택지 $index"
            etText.setText(text)
            etWeight.setText(weight.toString())

            // 선택지 내용 변경 시 편집모드 자동 전환
            etText.addTextChangedListener {
                if (!isEditMode) enterEditMode()
            }

            // 가중치 입력 감지 및 제한
            etWeight.addTextChangedListener {
                if (!isEditMode) enterEditMode()
                val value = it.toString().toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, 100)
                val exceptTotal = getTotalOptionWeightExcept(index - 1)
                val total = exceptTotal + clamped

                if (total > 100) {
                    val allowed = 100 - exceptTotal
                    etWeight.setText(allowed.coerceAtLeast(0).toString())
                    etWeight.setSelection(etWeight.text?.length ?: 0)
                    showToast("선택지 가중치 총합은 100을 초과할 수 없습니다. (최대 ${allowed}까지 가능)")
                }
            }

            binding.layoutOptionsContainer.addView(optionView)
        }

        private fun enterEditMode() {
            isEditMode = true
            with(binding) {
                etQuestion.isEnabled = true
                etQuestion.setBackgroundResource(android.R.drawable.edit_text)
                etQuestion.requestFocus()
                etQuestion.setSelection(etQuestion.text?.length ?: 0)

                btnEdit.visibility = View.GONE
                btnDelete.visibility = View.GONE
                editButtonsLayout.visibility = View.VISIBLE
                etWeight.isEnabled = true
                btnAddOption.visibility = View.VISIBLE
                btnRemoveOption.visibility = View.VISIBLE
            }
        }


        // 가중치 합 계산 (모두 포함)
        private fun getTotalOptionWeight(): Int {
            var total = 0
            for (i in 0 until binding.layoutOptionsContainer.childCount) {
                val optionView = binding.layoutOptionsContainer.getChildAt(i)
                val et = optionView.findViewById<TextInputEditText>(R.id.etOptionWeight)
                val value = et.text.toString().toIntOrNull() ?: 0
                total += value.coerceIn(0, 100)
            }
            return total
        }

        // 가중치 합 계산 (현재 선택지 미포함)
        private fun getTotalOptionWeightExcept(exceptIndex: Int): Int {
            var total = 0
            for (i in 0 until binding.layoutOptionsContainer.childCount) {
                if (i == exceptIndex) continue
                val optionView = binding.layoutOptionsContainer.getChildAt(i)
                val et = optionView.findViewById<TextInputEditText>(R.id.etOptionWeight)
                val value = et.text.toString().toIntOrNull() ?: 0
                total += value.coerceIn(0, 100)
            }
            return total
        }


        private fun exitEditMode() {
            isEditMode = false
            with(binding) {
                etQuestion.isEnabled = false
                etQuestion.setBackgroundResource(android.R.color.transparent)

                btnEdit.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                editButtonsLayout.visibility = View.GONE
                etWeight.isEnabled = false
                btnAddOption.visibility = View.GONE
                btnRemoveOption.visibility = View.GONE
            }
        }

        private fun showToast(msg: String) {
            android.widget.Toast.makeText(binding.root.context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChecklistConfig>() {
        override fun areItemsTheSame(oldItem: ChecklistConfig, newItem: ChecklistConfig): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChecklistConfig, newItem: ChecklistConfig): Boolean {
            return oldItem == newItem
        }
    }
}
