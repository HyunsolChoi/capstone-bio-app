// app/src/main/java/com/jjangdol/biorhythm/data/repository/UserNotificationRepository.kt
package com.jjangdol.biorhythm.data.repository

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.data.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자별 알림 상태 관리 Repository
 * 사용자가 읽은 알림 상태를 관리
 */
@Singleton
class UserNotificationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository
) {

    private val db: FirebaseFirestore = Firebase.firestore
    private val userNotificationsCollection = db.collection("userNotifications")

    // 현재 사용자 ID (UserRepository를 통해 일관된 방식으로 생성)
    private fun getCurrentUserId(): String? {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "") ?: ""
        val empNum = prefs.getString("emp_num", "") ?: ""

        android.util.Log.d("UserNotifRepo", "현재 로그인 사용자 → name=$name / empNum=$empNum")

        return if (name.isNotEmpty() && empNum.isNotEmpty()) {
            userRepository.getUserId(name, empNum)
        } else {
            null
        }
    }


    /**
     * 사용자가 읽은 알림 ID 목록을 실시간으로 관찰
     */
    fun getReadNotificationIds(): Flow<Set<String>> = callbackFlow {
        val userId = getCurrentUserId()
        if (userId == null) {
            trySend(emptySet())
            close()
            return@callbackFlow
        }

        val listener = userNotificationsCollection
            .document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val readIds = try {
                    snapshot?.get("readNotifications") as? List<String> ?: emptyList()
                } catch (e: Exception) {
                    emptyList<String>()
                }

                trySend(readIds.toSet())
            }

        awaitClose { listener.remove() }
    }

    /**
     * 알림을 읽음으로 표시
     */
    suspend fun markAsRead(notificationId: String): Result<Unit> {
        return try {
            val userId = getCurrentUserId() ?: return Result.failure(Exception("사용자 정보를 찾을 수 없습니다"))
            val docRef = userNotificationsCollection.document(userId)
            val notifDocRef = db.collection("notifications").document(notificationId)
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val empNum = prefs.getString("emp_num", "") ?: ""

            db.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val readList = (snapshot.get("readNotifications") as? List<String>)?.toMutableList()
                    ?: mutableListOf()

                if (notificationId !in readList) {
                    readList.add(notificationId)
                    transaction.set(docRef, mapOf(
                        "readNotifications" to readList,
                        "lastUpdated" to com.google.firebase.Timestamp.now()
                    ), com.google.firebase.firestore.SetOptions.merge())
                }
            }.await()

            notifDocRef.update("readBy", com.google.firebase.firestore.FieldValue.arrayUnion(empNum)).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 모든 알림을 읽음으로 표시
     */
    suspend fun markAllAsRead(): Result<Unit> {
        return try {
            val userId = getCurrentUserId() ?: return Result.failure(Exception("사용자 정보를 찾을 수 없습니다"))
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val empNum = prefs.getString("emp_num", "") ?: ""

            // 모든 활성 알림 ID 가져오기
            val allNotifications = db.collection("notifications")
                .whereEqualTo("active", true)
                .get()
                .await()

            val allNotificationIds = allNotifications.documents.map { it.id }

            val docRef = userNotificationsCollection.document(userId)
            docRef.set(mapOf(
                "readNotifications" to allNotificationIds,
                "lastUpdated" to com.google.firebase.Timestamp.now()
            ), com.google.firebase.firestore.SetOptions.merge()).await()

            allNotifications.documents.forEach { doc ->
                doc.reference.update("readBy", com.google.firebase.firestore.FieldValue.arrayUnion(empNum)).await()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 여러 알림을 읽음으로 표시
     */
    suspend fun markMultipleAsRead(notificationIds: List<String>): Result<Unit> {
        return try {
            val userId = getCurrentUserId() ?: return Result.failure(Exception("사용자 정보를 찾을 수 없습니다"))
            val docRef = userNotificationsCollection.document(userId)
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val empNum = prefs.getString("emp_num", "") ?: ""

            db.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val readList = (snapshot.get("readNotifications") as? List<String>)?.toMutableSet()
                    ?: mutableSetOf()

                readList.addAll(notificationIds)
                transaction.set(docRef, mapOf(
                    "readNotifications" to readList.toList(),
                    "lastUpdated" to com.google.firebase.Timestamp.now()
                ), com.google.firebase.firestore.SetOptions.merge())
            }.await()

            notificationIds.forEach { notificationId ->
                db.collection("notifications")
                    .document(notificationId)
                    .update("readBy", com.google.firebase.firestore.FieldValue.arrayUnion(empNum))
                    .await()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}