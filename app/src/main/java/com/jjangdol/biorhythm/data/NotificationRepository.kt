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

    private val db: FirebaseFirestore = Firebase.firestore
    private val notificationsCollection = db.collection("notifications")

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

            notificationsCollection.document(notificationId)
                .delete()  // update 대신 delete() 사용
                .await()
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
        targetDept: List<String>?,
        currentUserEmpNum: String
    ): Result<List<String>> {
        return try {
            val db = FirebaseFirestore.getInstance()
            var totalReads = 0

            // 현재 사용자의 부서 정보 가져오기
            val currentUserDoc = db.collection("employees").document(currentUserEmpNum).get().await()
            totalReads += 1

            val currentUserDeptPath = currentUserDoc.get("departmentPath") as? List<String>

            if (currentUserDeptPath.isNullOrEmpty()) {
                android.util.Log.e("NotificationRepo", "현재 사용자의 부서 정보가 없습니다")
                return Result.failure(Exception("부서 정보가 없습니다"))
            }

            // 알림에서 readBy 가져오기
            val notificationDoc = notificationsCollection.document(notificationId).get().await()
            totalReads += 1
            val readByEmpNums = notificationDoc.get("readBy") as? List<String> ?: emptyList()

            // 대상 직원 조회
            val allEmployees = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

            if (targetDept == null || targetDept.contains("전체")) {
                // 전체 대상이지만 내 부서의 하위만
                val myDeptRoot = currentUserDeptPath.last()  // 내 최하위 부서

                val query = db.collection("employees")
                    .whereArrayContains("departmentPath", myDeptRoot)  // 내 부서 하위만
                    .let { baseQuery ->
                        if (targetAuth != null && targetAuth < 2 && targetAuth >= 0) {
                            baseQuery.whereEqualTo("auth", targetAuth)
                        } else {
                            baseQuery
                        }
                    }

                val snapshot = query.get().await()
                totalReads += snapshot.size()
                allEmployees.addAll(snapshot.documents)

            } else {
                // 특정 부서 대상 - 내 부서 하위와 교집합
                val myDeptRoot = currentUserDeptPath.last()

                val chunks = targetDept.chunked(10)

                chunks.forEach { chunk ->
                    // 부서 필터 적용
                    val query = if (chunk.size == 1) {
                        db.collection("employees")
                            .whereArrayContains("departmentPath", chunk[0])
                    } else {
                        db.collection("employees")
                            .whereArrayContainsAny("departmentPath", chunk)
                    }

                    // auth 필터 추가
                    val finalQuery = if (targetAuth != null && targetAuth < 2 && targetAuth >= 0) {
                        query.whereEqualTo("auth", targetAuth)
                    } else {
                        query
                    }

                    val snapshot = finalQuery.get().await()
                    totalReads += snapshot.size()

                    // 내 부서 하위만 필터링
                    val filteredDocs = snapshot.documents.filter { doc ->
                        val deptPath = doc.get("departmentPath") as? List<String>
                        deptPath?.contains(myDeptRoot) == true
                    }

                    allEmployees.addAll(filteredDocs)
                }
            }

            if (allEmployees.isEmpty()) {
                android.util.Log.d("NotificationRepo", "📊 읽기 통계 - 총 읽기: ${totalReads}개, 결과: 0명")
                return Result.success(emptyList())
            }

            // 중복 제거
            val uniqueEmployees = allEmployees.distinctBy { it.id }

            // readBy에 없는 직원 찾기
            val unreadUsers = uniqueEmployees
                .filter { doc -> !readByEmpNums.contains(doc.id) }
                .mapNotNull { doc ->
                    try {
                        val name = doc.getString("Name") ?: doc.getString("name") ?: "이름 없음"
                        val empNum = doc.id
                        val departmentPath = doc.get("departmentPath") as? List<String>
                        val fullDept = if (!departmentPath.isNullOrEmpty()) {
                            departmentPath.last()
                        } else {
                            val deptString = doc.getString("dept") ?: "부서 미지정"
                            deptString.substringAfterLast("/").ifBlank { deptString }
                        }

                        // 내 부서 이후의 경로만 추출
                        val myDeptRoot = currentUserDeptPath.last()
                        val displayDept = if (fullDept.startsWith(myDeptRoot)) {
                            val relative = fullDept.removePrefix(myDeptRoot).removePrefix("/")
                            if (relative.isBlank()) "(동일 부서)" else relative
                        } else {
                            fullDept
                        }

                        "$name $empNum ($displayDept)"
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationRepo", "사용자 정보 파싱 실패: ${doc.id}", e)
                        null
                    }
                }

            Result.success(unreadUsers)

        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "읽지 않은 사용자 조회 실패", e)
            Result.failure(e)
        }
    }
}