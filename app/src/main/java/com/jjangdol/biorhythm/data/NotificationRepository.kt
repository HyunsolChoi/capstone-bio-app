// app/src/main/java/com/jjangdol/biorhythm/data/repository/NotificationRepository.kt
package com.jjangdol.biorhythm.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor() {

    private val firestore: FirebaseFirestore = Firebase.firestore
    private val notificationsCollection = firestore.collection("notifications")

    /**
     * 모든 활성 알림을 실시간으로 관찰 (필드명 수정: active)
     */
    fun getAllNotificationsForAdmin(): Flow<List<Notification>> = callbackFlow {
        val listener = notificationsCollection
            // 조건 없이 모든 알림 가져오기
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val notifications = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Notification::class.java)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                val sortedNotifications = notifications.sortedByDescending {
                    it.createdAt?.toDate()?.time ?: 0
                }

                trySend(sortedNotifications)
            }

        awaitClose { listener.remove() }
    }

    /**
     * 우선순위별 알림 조회 (필드명 수정: active)
     */
    fun getNotificationsByPriority(priority: NotificationPriority): Flow<List<Notification>> = callbackFlow {
        val listener = notificationsCollection
            .whereEqualTo("active", true)  // isActive -> active 변경
            .whereEqualTo("priority", priority.name)
            // .orderBy("createdAt", Query.Direction.DESCENDING) // 인덱스 에러 방지를 위해 주석
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val notifications = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject(Notification::class.java)
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
    }

    /**
     * 새 알림 생성 (필드명 수정: active)
     */
    suspend fun createNotification(
        title: String,
        content: String,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        attachmentUrl: List<String> = emptyList()
    ): Result<String> {
        return try {
            val notification = Notification(
                title = title,
                content = content,
                priority = priority,
                active = true,  // isActive -> active 변경
                createdBy = "admin",
                attachmentUrl = attachmentUrl
            )

            val documentRef = notificationsCollection.add(notification).await()
            Result.success(documentRef.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 알림 수정
     */
    suspend fun updateNotification(
        notificationId: String,
        title: String,
        content: String,
        priority: NotificationPriority
    ): Result<Unit> {
        return try {
            val updates = mapOf(
                "title" to title,
                "content" to content,
                "priority" to priority.name,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            notificationsCollection.document(notificationId)
                .update(updates)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNotification(notificationId: String): Result<Unit> {
        return try {
            android.util.Log.d("NotificationRepo", "알림 삭제 시작: $notificationId")

            notificationsCollection.document(notificationId)
                .delete()  // update 대신 delete() 사용
                .await()

            android.util.Log.d("NotificationRepo", "알림 삭제 완료: $notificationId")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "알림 삭제 실패: $notificationId, 에러: $e")
            Result.failure(e)
        }
    }

    /**
     * 특정 알림 조회
     */
    suspend fun getNotificationById(notificationId: String): Result<Notification?> {
        return try {
            val document = notificationsCollection.document(notificationId).get().await()
            val notification = document.toObject(Notification::class.java)
            Result.success(notification)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 알림 활성/비활성 토글
     */
    suspend fun toggleNotificationStatus(notificationId: String): Result<Unit> {
        return try {
            val document = notificationsCollection.document(notificationId).get().await()
            val currentStatus = document.getBoolean("active") ?: true

            notificationsCollection.document(notificationId)
                .update(
                    mapOf(
                        "active" to !currentStatus,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                )
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}