package com.jjangdol.biorhythm.model

import com.google.firebase.database.Exclude

/**
 * UI에서 실제 사용자가 보는 체크리스트 항목 모델.
 * ChecklistConfig 기반으로 생성되며, 사용자의 답을 담음.
 * Firestore 에서 Long으로 반환하기에 integer로 전환
 */
data class ChecklistItem(
    val id: String = "",
    val question: String = "",
    @get:Exclude private val _weight: Any? = null,
    val options: List<String>? = null,
    @get:Exclude private val _optionWeights: List<*>? = null,
    @get:Exclude private val _selectedOption: Any? = null
) {
    val weight: Int
        get() = when (_weight) {
            is Long -> _weight.toInt()
            is Int -> _weight
            else -> 0
        }

    val optionWeights: List<Int>?
        get() = _optionWeights?.mapNotNull {
            when (it) {
                is Long -> it.toInt()
                is Int -> it
                else -> null
            }
        }

    val selectedOption: Int?
        get() = when (_selectedOption) {
            is Long -> _selectedOption.toInt()
            is Int -> _selectedOption
            else -> null
        }
}
