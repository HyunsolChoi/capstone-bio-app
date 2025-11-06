package com.jjangdol.biorhythm.model

/**
 * UI에서 실제 사용자가 보는 체크리스트 항목 모델.
 * ChecklistConfig 기반으로 생성되며, 사용자의 답을 담음.
 */
data class ChecklistItem(
    val id: String = "",
    val question: String = "",
    val weight: Int = 0,                   // 문항 비중 (총합 100)
    val options: List<String>? = null,     // 선택지 텍스트
    val optionWeights: List<Int>? = null,  // 각 선택지별 가중치 (총합 100)
    val selectedOption: Int? = null        // 사용자가 선택한 보기 번호 (1~5)
)
