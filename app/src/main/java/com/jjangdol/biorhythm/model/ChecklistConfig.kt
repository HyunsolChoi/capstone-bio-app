package com.jjangdol.biorhythm.model

/**
 * Firestore 설정: 각 문항(question)에 부여된 가중치(weight)를 담는 데이터 모델
 * 문서 ID가 question 고유 ID로 사용.
 * 보기 별 상이한 가중치를 통해 추후 점수를 매김
 */
data class ChecklistConfig(
    val id: String = "",
    val order: Int = 0,
    val question: String = "",
    val weight: Int = 0,
    val options: List<String> = emptyList(),
    val optionWeights: List<Int> = emptyList()
)
