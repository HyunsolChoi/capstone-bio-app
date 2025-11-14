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
            // ✅ 로그: 함수 시작
            android.util.Log.d("NotificationRepo", "==================== getUnreadUsers 시작 ====================")
            android.util.Log.d("NotificationRepo", "알림 ID: $notificationId")
            android.util.Log.d("NotificationRepo", "대상 권한(auth): $targetAuth")
            android.util.Log.d("NotificationRepo", "대상 부서(targetDept): $targetDept")

            val db = FirebaseFirestore.getInstance()

            // 1. 알림에서 readBy 가져오기
            android.util.Log.d("NotificationRepo", "---------- 1단계: 읽은 사용자 조회 ----------")
            val notificationDoc = notificationsCollection.document(notificationId).get().await()
            val readByEmpNums = notificationDoc.get("readBy") as? List<String> ?: emptyList()
            android.util.Log.d("NotificationRepo", "읽은 사용자 수: ${readByEmpNums.size}명")
            android.util.Log.d("NotificationRepo", "읽은 사용자 목록: $readByEmpNums")

            // 2. ✅여기가 수정됨 - departmentPath를 사용한 효율적인 쿼리
            android.util.Log.d("NotificationRepo", "---------- 2단계: 대상 직원 조회 ----------")
            val allEmployees = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

            if (targetDept == null || targetDept.contains("전체")) {
                // ✅ 전체 대상 - auth만 필터링
                android.util.Log.d("NotificationRepo", "📢 전체 부서 대상 쿼리 실행")
                android.util.Log.d("NotificationRepo", "쿼리 조건: auth = $targetAuth")

                val snapshot = if (targetAuth != null && targetAuth != 2) {
                    android.util.Log.d("NotificationRepo", "Int 타입으로 auth 쿼리 시도...")
                    var result = db.collection("employees")
                        .whereEqualTo("auth", targetAuth)
                        .get()
                        .await()

                    android.util.Log.d("NotificationRepo", "Int 쿼리 결과: ${result.size()}명")

                    // auth가 Long 타입일 수도 있으므로 재시도
                    if (result.isEmpty) {
                        android.util.Log.d("NotificationRepo", "결과 없음. Long 타입으로 재시도...")
                        result = db.collection("employees")
                            .whereEqualTo("auth", targetAuth.toLong())
                            .get()
                            .await()
                        android.util.Log.d("NotificationRepo", "Long 쿼리 결과: ${result.size()}명")
                    }
                    result
                } else {
                    android.util.Log.d("NotificationRepo", "auth 필터링 없음. 전체 직원 조회...")
                    db.collection("employees").get().await()
                }

                allEmployees.addAll(snapshot.documents)
                android.util.Log.d("NotificationRepo", "✅ 전체 조회 완료: ${allEmployees.size}명")

            } else {
                // ✅여기가 수정됨 - departmentPath 배열을 사용한 효율적인 쿼리
                android.util.Log.d("NotificationRepo", "📢 특정 부서 대상 쿼리 실행")
                android.util.Log.d("NotificationRepo", "대상 부서 개수: ${targetDept.size}개")
                android.util.Log.d("NotificationRepo", "대상 부서 목록: $targetDept")

                // Firestore의 array-contains-any는 최대 10개까지만 가능하므로 배치 처리
                val chunks = targetDept.chunked(10)
                android.util.Log.d("NotificationRepo", "배치 처리: ${chunks.size}개 청크로 분할 (10개씩)")

                chunks.forEachIndexed { index, chunk ->
                    android.util.Log.d("NotificationRepo", "--- 청크 ${index + 1}/${chunks.size} 쿼리 시작 ---")
                    android.util.Log.d("NotificationRepo", "청크 내용: $chunk")

                    try {
                        val baseQuery = if (targetAuth != null && targetAuth != 2) {
                            android.util.Log.d("NotificationRepo", "쿼리 조건: auth = $targetAuth")
                            db.collection("employees").whereEqualTo("auth", targetAuth)
                        } else {
                            android.util.Log.d("NotificationRepo", "쿼리 조건: auth 필터 없음")
                            db.collection("employees")
                        }

                        val query = if (chunk.size == 1) {
                            // ✅ 단일 부서 검색 - array-contains 사용
                            android.util.Log.d("NotificationRepo", "🔍 whereArrayContains 사용: ${chunk[0]}")
                            baseQuery.whereArrayContains("departmentPath", chunk[0])
                        } else {
                            // ✅ 여러 부서 검색 - array-contains-any 사용 (최대 10개)
                            android.util.Log.d("NotificationRepo", "🔍 whereArrayContainsAny 사용: ${chunk.size}개 부서")
                            baseQuery.whereArrayContainsAny("departmentPath", chunk)
                        }

                        val snapshot = query.get().await()
                        android.util.Log.d("NotificationRepo", "✅ 청크 조회 성공: ${snapshot.size()}명")

                        // ✅ 로그: 조회된 직원 상세 정보
                        snapshot.documents.forEachIndexed { empIndex, doc ->
                            val empNum = doc.id
                            val name = doc.getString("Name") ?: doc.getString("name") ?: "이름없음"
                            val deptPath = doc.get("departmentPath") as? List<String> ?: emptyList()
                            android.util.Log.d("NotificationRepo", "  ${empIndex + 1}. $name ($empNum) - 부서경로: $deptPath")
                        }

                        allEmployees.addAll(snapshot.documents)
                        android.util.Log.d("NotificationRepo", "현재까지 누적: ${allEmployees.size}명")

                    } catch (e: Exception) {
                        android.util.Log.e("NotificationRepo", "❌ 청크 쿼리 실패", e)
                        android.util.Log.e("NotificationRepo", "에러 메시지: ${e.message}")

                        // ✅ Long 타입으로 재시도
                        if (targetAuth != null && targetAuth != 2) {
                            android.util.Log.d("NotificationRepo", "Long 타입으로 재시도...")
                            try {
                                val baseQuery = db.collection("employees")
                                    .whereEqualTo("auth", targetAuth.toLong())

                                val query = if (chunk.size == 1) {
                                    baseQuery.whereArrayContains("departmentPath", chunk[0])
                                } else {
                                    baseQuery.whereArrayContainsAny("departmentPath", chunk)
                                }

                                val snapshot = query.get().await()
                                allEmployees.addAll(snapshot.documents)
                                android.util.Log.d("NotificationRepo", "✅ 재시도 성공: ${snapshot.size()}명")
                            } catch (retryError: Exception) {
                                android.util.Log.e("NotificationRepo", "❌ 재시도도 실패", retryError)
                            }
                        }
                    }
                }

                android.util.Log.d("NotificationRepo", "✅ 모든 청크 조회 완료. 총 ${allEmployees.size}명")
            }

            if (allEmployees.isEmpty()) {
                android.util.Log.d("NotificationRepo", "⚠️ 조회된 직원 없음")
                return Result.success(emptyList())
            }

            // 3. ✅ 중복 제거 (10개씩 쿼리할 때 중복 가능)
            android.util.Log.d("NotificationRepo", "---------- 3단계: 중복 제거 ----------")
            android.util.Log.d("NotificationRepo", "중복 제거 전: ${allEmployees.size}명")
            val uniqueEmployees = allEmployees.distinctBy { it.id }
            android.util.Log.d("NotificationRepo", "중복 제거 후: ${uniqueEmployees.size}명")

            if (allEmployees.size > uniqueEmployees.size) {
                val duplicateCount = allEmployees.size - uniqueEmployees.size
                android.util.Log.d("NotificationRepo", "⚠️ 중복 제거됨: ${duplicateCount}명")
            }

            // 4. readBy에 없는 직원 찾기
            android.util.Log.d("NotificationRepo", "---------- 4단계: 읽지 않은 사용자 필터링 ----------")
            val unreadUsers = uniqueEmployees
                .filter { doc ->
                    val empNum = doc.id
                    val isRead = readByEmpNums.contains(empNum)

                    // ✅ 로그: 각 직원의 읽음 상태
                    val name = doc.getString("Name") ?: doc.getString("name") ?: "이름없음"
                    val status = if (isRead) "✓ 읽음" else "✗ 안읽음"
                    android.util.Log.d("NotificationRepo", "  $name ($empNum): $status")

                    !isRead
                }
                .mapNotNull { doc ->
                    try {
                        val name = doc.getString("Name") ?: doc.getString("name") ?: "이름 없음"
                        val empNum = doc.id

                        // ✅ departmentPath에서 마지막 부서명 가져오기
                        val departmentPath = doc.get("departmentPath") as? List<String>
                        val deptDisplay = if (!departmentPath.isNullOrEmpty()) {
                            departmentPath.last() // 마지막 경로가 실제 소속 부서
                        } else {
                            // fallback: 기존 dept 필드 사용
                            val deptString = doc.getString("dept") ?: "부서 미지정"
                            deptString.substringAfterLast("/").ifBlank { deptString }
                        }

                        "$name $empNum ($deptDisplay)"
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationRepo", "❌ 사용자 정보 파싱 실패: ${doc.id}", e)
                        null
                    }
                }

            // ✅ 로그: 최종 결과
            android.util.Log.d("NotificationRepo", "---------- 최종 결과 ----------")
            android.util.Log.d("NotificationRepo", "✅ 읽지 않은 사용자: ${unreadUsers.size}명")
            android.util.Log.d("NotificationRepo", "읽지 않은 사용자 목록:")
            unreadUsers.forEachIndexed { index, user ->
                android.util.Log.d("NotificationRepo", "  ${index + 1}. $user")
            }
            android.util.Log.d("NotificationRepo", "==================== getUnreadUsers 종료 ====================")

            Result.success(unreadUsers)

        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "❌❌❌ 읽지 않은 사용자 조회 실패 ❌❌❌", e)
            android.util.Log.e("NotificationRepo", "에러 타입: ${e.javaClass.simpleName}")
            android.util.Log.e("NotificationRepo", "에러 메시지: ${e.message}")
            android.util.Log.e("NotificationRepo", "스택 트레이스:", e)
            Result.failure(e)
        }
    }
}