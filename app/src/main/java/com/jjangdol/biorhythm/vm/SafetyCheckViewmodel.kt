package com.jjangdol.biorhythm.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.model.*
import com.jjangdol.biorhythm.util.ScoreCalculator
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class SafetyCheckViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository,
    private val application: Application
) : AndroidViewModel(application) {

    private val _currentSession = MutableStateFlow<SafetyCheckSession?>(null)
    val currentSession: StateFlow<SafetyCheckSession?> = _currentSession.asStateFlow()

    private val _sessionState = MutableLiveData<SessionState>(SessionState.Idle)
    val sessionState: LiveData<SessionState> = _sessionState

    // 체크리스트 상태 관리용 LiveData 추가
    private val _checklistAnswers = MutableLiveData<MutableMap<String, Any>>(mutableMapOf())
    val checklistAnswers: LiveData<MutableMap<String, Any>> = _checklistAnswers

    private val _checklistScore = MutableLiveData<Int>(0)
    val checklistScore: LiveData<Int> = _checklistScore


    private val dateFormatter = DateTimeFormatter.ISO_DATE

    // 체크리스트 점수를 메모리에 저장
    private var savedChecklistScore: Int = 0

    sealed class SessionState {
        object Idle : SessionState()
        object Loading : SessionState()
        data class Success(val message: String) : SessionState()
        data class Error(val message: String) : SessionState()
    }

    private fun getUserId(): String? {
        val prefs = application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val dept = prefs.getString("user_dept", "") ?: ""
        val name = prefs.getString("user_name", "") ?: ""
        val dob = prefs.getString("dob", "") ?: ""
        val Enum = prefs.getString("user_Enum", "") ?: ""

        return if (dept.isNotEmpty() && name.isNotEmpty() && dob.isNotEmpty()) {
            userRepository.getUserId(name, Enum)
        } else {
            null
        }
    }

    private fun getUserProfile(): Triple<String, String, String>? {
        val prefs = application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val dept = prefs.getString("user_dept", "") ?: ""
        val name = prefs.getString("user_name", "") ?: ""
        val dob = prefs.getString("dob", "") ?: ""

        return if (dept.isNotEmpty() && name.isNotEmpty() && dob.isNotEmpty()) {
            Triple(dept, name, dob)
        } else {
            null
        }
    }

    fun startNewSession(session: SafetyCheckSession) {
        _currentSession.value = session
    }

    fun updateChecklistResults(
        checklistItems: List<ChecklistItem>,
        checklistScore: Int
    ) {
        // 점수를 메모리에 저장
        this.savedChecklistScore = checklistScore

        // LiveData 업데이트
        _checklistScore.value = checklistScore

        _currentSession.value?.let { session ->
            _currentSession.value = session.copy(
                checklistResults = checklistItems
            )
        }
    }

    // 체크리스트 답변 업데이트 메서드
    fun updateChecklistAnswer(questionId: String, answer: Any) {
        val currentAnswers = _checklistAnswers.value ?: mutableMapOf()
        currentAnswers[questionId] = answer
        _checklistAnswers.value = currentAnswers
    }

    // 체크리스트 초기화 메서드
    fun resetChecklist() {
        // 체크리스트 관련 모든 상태 초기화
        _checklistAnswers.value = mutableMapOf()
        _checklistScore.value = 0

        // 저장된 점수도 초기화
        savedChecklistScore = 0

        // 현재 세션의 체크리스트 결과도 초기화
        _currentSession.value?.let { session ->
            _currentSession.value = session.copy(
                checklistResults = emptyList()
            )
        }
    }

    // 체크리스트 완료 상태 확인 메서드
    fun isChecklistCompleted(): Boolean {
        val answers = _checklistAnswers.value ?: return false
        // 필수 질문들이 모두 답변되었는지 확인하는 로직
        // 실제 구현은 체크리스트 항목 수에 따라 조정 필요
        return answers.size >= getRequiredQuestionCount()
    }

    private fun getRequiredQuestionCount(): Int {
        // 실제 체크리스트 필수 질문 수를 반환
        // 이 값은 실제 체크리스트 구조에 맞게 조정하세요
        return 10 // 예시값
    }

    fun addMeasurementResult(result: MeasurementResult) {
        _currentSession.value?.let { session ->
            session.measurementResults.add(result)
            _currentSession.value = session.copy(
                measurementResults = session.measurementResults
            )
        }
    }

    fun completeSession(onComplete: (SafetyCheckResult) -> Unit) {
        viewModelScope.launch {
            _sessionState.value = SessionState.Loading

            try {
                val session = _currentSession.value ?: throw Exception("세션이 없습니다")
                val userId = getUserId() ?: throw Exception("사용자 정보를 찾을 수 없습니다")
                val userProfile = getUserProfile() ?: throw Exception("사용자 프로필을 찾을 수 없습니다")

                val (dept, name, _) = userProfile

                // 저장된 점수 사용
                val checklistScore = savedChecklistScore

                // 측정 결과 점수 추출
                val tremorScore = session.measurementResults
                    .find { it.type == MeasurementType.TREMOR }?.score ?: 0f
                val pupilScore = session.measurementResults
                    .find { it.type == MeasurementType.PUPIL }?.score ?: 0f
                val ppgScore = session.measurementResults
                    .find { it.type == MeasurementType.PPG }?.score ?: 0f

                // 최종 안전 점수 계산
                val finalScore = ScoreCalculator.calcFinalSafetyScore(
                    checklistScore = checklistScore,
                    tremorScore = tremorScore,
                    pupilScore = pupilScore,
                    ppgScore = ppgScore
                )

                // 안전 레벨 결정
                val safetyLevel = SafetyLevel.fromScore(finalScore)

                // 권고사항 생성
                val recommendations = generateRecommendations(
                    safetyLevel, tremorScore, pupilScore, ppgScore
                )

                // 최종 결과 객체 생성
                val result = SafetyCheckResult(
                    userId = userId,
                    name = name,
                    dept = dept,
                    checklistScore = checklistScore,
                    tremorScore = tremorScore,
                    pupilScore = pupilScore,
                    ppgScore = ppgScore,
                    finalSafetyScore = finalScore,
                    safetyLevel = safetyLevel,
                    date = LocalDate.now().format(dateFormatter),
                    recommendations = recommendations
                )

                // Firestore에 저장
                saveResultToFirestore(result)

                // 세션 완료 처리
                _currentSession.value = session.copy(isCompleted = true)
                _sessionState.value = SessionState.Success("안전 체크 완료")

                onComplete(result)

            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("세션 완료 실패: ${e.message}")
            }
        }
    }

    private fun generateRecommendations(
        level: SafetyLevel,
        tremorScore: Float,
        pupilScore: Float,
        ppgScore: Float
    ): List<String> {
        val recommendations = mutableListOf<String>()

        when (level) {
            SafetyLevel.DANGER -> {
                recommendations.add("즉시 휴식을 취하시고 관리자에게 보고하세요")
                recommendations.add("충분한 수분 섭취와 휴식이 필요합니다")
            }
            SafetyLevel.CAUTION -> {
                recommendations.add("가벼운 스트레칭 후 작업을 시작하세요")
                recommendations.add("주기적으로 휴식을 취하며 작업하세요")
            }
            SafetyLevel.SAFE -> {
                recommendations.add("안전한 상태입니다. 작업을 진행하세요")
            }
        }

        // 개별 측정 결과에 따른 권고사항
        if (tremorScore > 0 && tremorScore < 70) {
            recommendations.add("손떨림이 감지됩니다. 정밀 작업 시 주의하세요")
        }
        if (pupilScore > 0 && pupilScore < 70) {
            recommendations.add("피로도가 높습니다. 충분한 휴식을 취하세요")
        }
        if (ppgScore > 0 && ppgScore < 70) {
            recommendations.add("심박이 불안정합니다. 스트레스 관리가 필요합니다")
        }

        return recommendations
    }

    private suspend fun saveResultToFirestore(result: SafetyCheckResult) {
        val today = result.date
        val userId = result.userId

        // 기존 구조 유지 (일별 결과)
        firestore.collection("results")
            .document(today)
            .collection("entries")
            .document(userId)
            .set(result)
            .await()

        // 사용자별 이력
        firestore.collection("results")
            .document(userId)
            .collection("daily")
            .document(today)
            .set(result)
            .await()

        // 안전 체크 전용 컬렉션에도 저장
        val sessionId = _currentSession.value?.sessionId ?: ""
        if (sessionId.isNotEmpty()) {
            firestore.collection("safety_checks")
                .document(userId)
                .collection("sessions")
                .document(sessionId)
                .set(mapOf(
                    "result" to result,
                    "session" to _currentSession.value
                ))
                .await()
        }
    }

    // 세션 클리어 메서드
    fun clearSession() {
        _currentSession.value = null
        _sessionState.value = SessionState.Idle
        // 체크리스트는 별도 메서드로 초기화하므로 여기서는 세션만 초기화
    }

    // 완전 초기화 메서드 (모든 상태 초기화)
    fun clearAll() {
        clearSession()
        resetChecklist()
    }

    override fun onCleared() {
        super.onCleared()
        clearAll()
    }
}