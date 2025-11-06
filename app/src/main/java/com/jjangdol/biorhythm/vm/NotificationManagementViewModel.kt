// app/src/main/java/com/jjangdol/biorhythm/vm/NotificationManagementViewModel.kt
package com.jjangdol.biorhythm.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.content.ContentResolver
import android.database.Cursor
import android.provider.OpenableColumns
import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@HiltViewModel
class NotificationManagementViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository, @ApplicationContext private val appContext: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _filterPriority = MutableStateFlow<NotificationPriority?>(null)

    private val allNotifications = notificationRepository.getAllNotificationsForAdmin()

    private val storage = com.google.firebase.storage.FirebaseStorage.getInstance("gs://bio-app-d2b71.firebasestorage.app")

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

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class Success(val message: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    //로그인 보장
    private suspend fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
            Log.d("NotificationVM", "Signed in anonymously. uid=${auth.currentUser?.uid}")
        }
    }

    private fun guessFileName(resolver: ContentResolver, uri: Uri): String {
        val nameFromResolver = resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && c.moveToFirst()) c.getString(idx) else null
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        return (nameFromResolver ?: fallback).ifBlank { "attachment" }
    }

    private fun coerceMime(resolver: ContentResolver, uri: Uri, fileName: String): String {
        val detected = resolver.getType(uri)
        if (!detected.isNullOrBlank()) return detected
        return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private fun buildStoragePath(fileName: String): String {
        val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
        return "notifications/$date/${UUID.randomUUID()}_$fileName"
    }


    private suspend fun uploadOne(resolver: ContentResolver, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val fileName = guessFileName(resolver, uri)
            val path = buildStoragePath(fileName)
            val ref = storage.reference.child(path)
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val meta = StorageMetadata.Builder().setContentType(mime).build()

            Log.d(
                "NotificationVM",
                "Try upload: uid=${FirebaseAuth.getInstance().currentUser?.uid}, path=$path, mime=$mime"
            )

            try {
                ref.putFile(uri, meta).await()
                val url = ref.downloadUrl.await().toString()
                Log.d("NotificationVM", "Upload OK: $url")
                url
            } catch (e: Exception) {
                if (e is StorageException) {
                    when (e.errorCode) {
                        StorageException.ERROR_NOT_AUTHENTICATED -> Log.e("NotificationVM", "인증 안됨", e)
                        StorageException.ERROR_NOT_AUTHORIZED    -> Log.e("NotificationVM", "권한 없음(규칙 불일치)", e)
                        StorageException.ERROR_OBJECT_NOT_FOUND  -> Log.e("NotificationVM", "경로 없음", e)
                        else                                     -> Log.e("NotificationVM", "기타 Storage 오류 code=${e.errorCode}", e)
                    }
                } else {
                    Log.e("NotificationVM", "기타 예외", e)
                }
                throw e
            }
        }

    private suspend fun uploadAttachments(uris: List<Uri>): List<String> {
        if (uris.isEmpty()) return emptyList()
        val resolver = appContext.contentResolver
        return uris.map { uploadOne(resolver, it) }
    }

    fun createNotification(
        title: String,
        content: String,
        priority: NotificationPriority,
        attachments: List<Uri> = emptyList()
    ) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try
            {
                ensureSignedIn()

                val urls = uploadAttachments(attachments)
                notificationRepository.createNotification(
                    title = title,
                    content = content,
                    priority = priority,
                    attachmentUrl = urls
                ).onSuccess {
                    _uiState.value = UiState.Success("알림이 성공적으로 등록되었습니다")
                }.onFailure { e ->
                    Log.e("NotificationVM", "알림 등록 실패", e)
                    _uiState.value = UiState.Error("알림 등록에 실패했습니다: ${e.message}")
                }
            } catch (e: Exception) {
                val user = FirebaseAuth.getInstance().currentUser
                Log.e("NotificationVM", buildString {
                    appendLine("Upload failed in createNotification")
                    appendLine("uid=${user?.uid ?: "null"}")
                    // refPath는 개별 업로드에서 이미 상세 로그를 남기므로 루트만 남깁니다.
                    appendLine("rootRef=${storage.reference.bucket}/${storage.reference.path}")
                }, e)

                _uiState.value = UiState.Error("첨부 업로드 실패: ${e.message}")
            } finally {
                _uiState.value = UiState.Idle
            }
        }
    }

    fun updateNotification(
        notificationId: String,
        title: String,
        content: String,
        priority: NotificationPriority
    ) {
        viewModelScope.launch {
            notificationRepository.updateNotification(notificationId, title, content, priority)
                .onSuccess {
                    _uiState.value = UiState.Success("알림이 수정되었습니다")
                }
                .onFailure { exception ->
                    _uiState.value = UiState.Error("알림 수정에 실패했습니다: ${exception.message}")
                }
        }
    }

    fun deleteNotification(notificationId: String) {
        viewModelScope.launch {
            notificationRepository.deleteNotification(notificationId)
                .onSuccess {
                    _uiState.value = UiState.Success("알림이 삭제되었습니다")
                }
                .onFailure { exception ->
                    _uiState.value = UiState.Error("알림 삭제에 실패했습니다: ${exception.message}")
                }
        }
    }

    fun toggleNotificationStatus(notificationId: String) {
        viewModelScope.launch {
            notificationRepository.toggleNotificationStatus(notificationId)
                .onSuccess {
                    _uiState.value = UiState.Success("알림 상태가 변경되었습니다")
                }
                .onFailure { exception ->
                    _uiState.value = UiState.Error("상태 변경에 실패했습니다: ${exception.message}")
                }
        }
    }

    fun setFilter(priority: NotificationPriority?) {
        _filterPriority.value = priority
    }

    fun refreshNotifications() {
        // 새로고침 로직 (Repository에서 자동으로 실시간 업데이트됨)
        _uiState.value = UiState.Success("목록을 새로고침했습니다")
    }
}