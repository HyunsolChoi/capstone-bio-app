package com.jjangdol.biorhythm.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

/* ---------- 측정 타입 ---------- */
enum class MeasurementType(val displayName: String, val description: String) {
    TREMOR("손떨림 측정", "스마트폰을 들고 10초간 측정합니다"),
    PUPIL("피로도 측정", "카메라를 통해 눈 상태를 확인합니다"),
    PPG("심박 측정", "카메라에 손가락을 대고 10초간 측정합니다")
}

/* ---------- 측정 결과 ---------- */
@Parcelize
data class MeasurementResult(
    val type: MeasurementType,
    val score: Float,
    val rawData: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) : Parcelable

/* ---------- 측정 세션 ---------- */
@Parcelize
data class SafetyCheckSession(
    val sessionId: String,
    val userId: String,
    val startTime: Long,
    val checklistResults: @RawValue List<ChecklistItem>? = null,
    val measurementResults: @RawValue MutableList<MeasurementResult> = mutableListOf(),
    var isCompleted: Boolean = false
) : Parcelable

/* ---------- 최종 결과 ---------- */
data class SafetyCheckResult(
    val userId: String = "",
    val empNum: String = "",
    val name: String = "",
    val dept: List<String> = emptyList(),
    val checklistScore: Int = 0,
    val tremorScore: Float = 0f,
    val pupilScore: Float = 0f,
    val ppgScore: Float = 0f,
    val finalSafetyScore: Float = 0f,
    val safetyLevel: SafetyLevel = SafetyLevel.fromScore(finalSafetyScore),
    val date: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val recommendations: List<String> = emptyList()
)

/* ---------- 안전 레벨 ---------- */
enum class SafetyLevel(val displayName: String, val color: String, val minScore: Float) {
    SAFE("안전",   "#4CAF50", 80f),
    CAUTION("주의", "#FFC107", 60f),
    DANGER("위험",  "#F44336", 0f);

    companion object {
        fun fromScore(score: Float) = values().first { score >= it.minScore }
    }
}

/* ---------- 측정 상태 ---------- */
sealed class MeasurementState {
    object Idle : MeasurementState()
    object Preparing : MeasurementState()
    data class InProgress(val progress: Float) : MeasurementState()
    data class Completed(val result: MeasurementResult) : MeasurementState()
    data class Error(val message: String) : MeasurementState()
}
