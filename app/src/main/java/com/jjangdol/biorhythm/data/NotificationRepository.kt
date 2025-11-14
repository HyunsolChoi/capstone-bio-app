// app/src/main/java/com/jjangdol/biorhythm/data/repository/NotificationRepository.kt
package com.jjangdol.biorhythm.data.repository

import com.google.firebase.firestore.FirebaseFirestore
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
import com.google.firebase.firestore.Query

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
     * 새 알림 생성 (필드명 수정: active)
     */
    suspend fun createNotification(
        title: String,
        content: String,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        attachmentUrl: List<String> = emptyList(),
        auth: Int,
        targetDept: List<String>,
        readBy: List<String> = emptyList()
    ): Result<String> {
        return try {
            val notification = Notification(
                title = title,
                content = content,
                priority = priority,
                active = true,
                createdBy = "admin",
                attachmentUrl = attachmentUrl,
                auth = auth,
                targetDept = targetDept,
                readBy = readBy
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
        priority: NotificationPriority,
        auth: Int? = null,
        targetDept: List<String>? = null,
        attachmentUrl: List<String>? = null,
    ): Result<Unit> {
        return try {
            val updates = mutableMapOf<String, Any>(
                "title" to title,
                "content" to content,
                "priority" to priority.name,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            auth?.let { updates["auth"] = it }
            targetDept?.let { updates["targetDept"] = it }

            // 선택적으로 첨부파일 업데이트
            attachmentUrl?.let { updates["attachmentUrl"] = it }

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

    //읽지 않은 사용자 조회
    suspend fun getUnreadUsers(
        notificationId: String,
        targetAuth: Int?,
        targetDept: List<String>?
    ): Result<List<String>> {
        return try {
            val db = FirebaseFirestore.getInstance()

            // 알림에서 readBy 가져오기
            val notificationDoc = notificationsCollection.document(notificationId).get().await()
            val readByEmpNums = notificationDoc.get("readBy") as? List<String> ?: emptyList()

            // Auth 필터링
            val allEmployees = if (targetAuth != null && targetAuth != 2) {
                var result = db.collection("employees").whereEqualTo("auth", targetAuth).get().await()

                if (result.isEmpty) {
                    result = db.collection("employees").whereEqualTo("auth", targetAuth.toLong()).get().await()
                }
                result
            } else{ db.collection("employees").get().await() }

            if (allEmployees.isEmpty) { return Result.success(emptyList()) }

            // targetDept 필터링
            val targetEmployees = if (targetDept != null && !targetDept.contains("전체")) {
                val filtered = allEmployees.documents.filter { doc ->
                    val empDeptString = doc.getString("dept") ?: ""

                    val isMatch = targetDept.any { target ->
                        empDeptString.contains(target, ignoreCase = true) ||
                                target.contains(empDeptString, ignoreCase = true)
                    }
                    isMatch
                }
                filtered
            } else { allEmployees.documents }

            if (targetEmployees.isEmpty()) { return Result.success(emptyList()) }

            // readBy에 없는 직원 찾기
            val unreadUsers = targetEmployees
                .filter { doc ->
                    val empNum = doc.id
                    val isRead = readByEmpNums.contains(empNum)
                    !isRead
                }
                .map { doc ->
                    val name = doc.getString("Name") ?: "이름 없음"
                    val empNum = doc.id
                    val deptString = doc.getString("dept") ?: "부서 미지정"
                    val deptDisplay = deptString.substringAfterLast("/").ifBlank { deptString }
                    "$name $empNum ($deptDisplay)"
                }
            Result.success(unreadUsers)

        } catch (e: Exception) { Result.failure(e) }
    }
}