// app/src/main/java/com/jjangdol/biorhythm/vm/UserNotificationViewModel.kt
package com.jjangdol.biorhythm.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.data.repository.UserNotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class UserNotificationViewModel @Inject constructor(
    private val userNotificationRepository: UserNotificationRepository
) : ViewModel() {

    sealed class AttachmentEvent
    {
        data class Open(val url: String) : AttachmentEvent()
        data class Download(val url: String, val fileName: String) : AttachmentEvent()
        data class Message(val text: String) : AttachmentEvent()
    }

    private val _attachmentEvents = MutableSharedFlow<AttachmentEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val attachmentEvents = _attachmentEvents.asSharedFlow()

    private val firestore = Firebase.firestore
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _filterPriority = MutableStateFlow<NotificationPriority?>(null)

    // 사용자용: 활성 알림만 가져오기
    private val allNotifications = callbackFlow {
        val listener = firestore.collection("notifications")
            .whereEqualTo("active", true)  // 사용자는 활성 알림만
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val notifications = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Notification::class.java)?.copy(id = doc.id)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                // 클라이언트 사이드에서 정렬
                val sortedNotifications = notifications.sortedByDescending {
                    it.createdAt?.toDate()?.time ?: 0
                }

                trySend(sortedNotifications)
            }

        awaitClose { listener.remove() }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // 읽은 알림 ID 목록
    private val readNotificationIds = userNotificationRepository
        .getReadNotificationIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    // 필터링된 알림 목록 (모든 알림 표시, 읽음 상태는 별도 처리)
    val notifications = combine(
        allNotifications,
        _filterPriority
    ) { notifications, filter ->
        if (filter == null) {
            notifications
        } else {
            notifications.filter { it.priority == filter }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // 읽지 않은 알림 개수
    val unreadCount = combine(
        allNotifications,
        readNotificationIds
    ) { notifications, readIds ->
        notifications.count { it.id !in readIds }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    // 읽음 상태 확인 함수
    fun isNotificationRead(notificationId: String): Boolean {
        return readNotificationIds.value.contains(notificationId)
    }

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class Success(val message: String = "") : UiState()
        data class Error(val message: String) : UiState()
    }

    fun setFilter(priority: NotificationPriority?) {
        _filterPriority.value = priority
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            userNotificationRepository.markAsRead(notificationId)
                .onSuccess {
                    _uiState.value = UiState.Success("읽음 처리되었습니다")
                    // 읽음 처리되었습니다 메시지가 지속적으로 뜨는 현상 방지
                    _uiState.value = UiState.Idle
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("읽음 처리에 실패했습니다: ${e.message}")
                    _uiState.value = UiState.Idle
                }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            userNotificationRepository.markAllAsRead()
                .onSuccess {
                    _uiState.value = UiState.Success("모든 알림을 읽음으로 처리했습니다")
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("읽음 처리에 실패했습니다: ${e.message}")
                }
        }
    }

    fun markMultipleAsRead(notificationIds: List<String>) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            userNotificationRepository.markMultipleAsRead(notificationIds)
                .onSuccess {
                    _uiState.value = UiState.Success("선택한 알림을 읽음으로 처리했습니다")
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("읽음 처리에 실패했습니다: ${e.message}")
                }
        }
    }

    fun refreshNotifications() {
        _uiState.value = UiState.Success("알림을 새로고침했습니다")
    }

    //첨부파일
    private fun guessFileName(url: String): String {
        val cleaned = url.substringBefore('?')
        val name = cleaned.substringAfterLast('/')
        return if (name.isBlank()) "attachment" else name
    }

    fun requestOpenAttachment(url: String) {
        viewModelScope.launch {
            if (url.isBlank())
            {
                _attachmentEvents.emit(AttachmentEvent.Message("잘못된 URL 입니다."))
            }
            else
            {
                _attachmentEvents.emit(AttachmentEvent.Open(url))
            }
        }
    }

    fun requestDownloadAttachment(url: String) {
        viewModelScope.launch {
            if (url.isBlank())
            {
                _attachmentEvents.emit(AttachmentEvent.Message("잘못된 URL 입니다."))
            }
            else
            {
                _attachmentEvents.emit(AttachmentEvent.Download(url, guessFileName(url)))
            }
        }
    }
}