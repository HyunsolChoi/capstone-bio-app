// app/src/main/java/com/jjangdol/biorhythm/data/ResultsRepository.kt
package com.jjangdol.biorhythm.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.model.ChecklistResult
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import javax.inject.Inject

@ActivityRetainedScoped
class ResultsRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    private val fmt = DateTimeFormatter.ISO_DATE

    // 금일 동일 소속 혹은 하위 직원 결과 가지고 오기
    // todo : 실제로 호출되지도 않음, byData만 쓰는데 이거 왜 있음??
    fun watchTodayResults(context: Context): Flow<List<ChecklistResult>> = callbackFlow {
//        val TAG = "watchTodayResults"
//        Log.d(TAG, "=== Flow 시작 ===")
//
//        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
//        val myDeptStr = prefs.getString("dept", "") ?: ""
//        Log.d(TAG, "내 부서 문자열: '$myDeptStr'")
//
//        val myDeptList = when {
//            myDeptStr == "미등록" -> emptyList()
//            myDeptStr.isNotEmpty() -> myDeptStr.split(",").map { it.trim() }
//            else -> emptyList()
//        }
//        Log.d(TAG, "내 부서 리스트: $myDeptList (size=${myDeptList.size})")
//
//        // 미등록 또는 비어있으면 바로 종료
//        if (myDeptList.isEmpty()) {
//            Log.d(TAG, "부서가 비어있음 - 빈 리스트 반환 후 종료")
//            trySend(emptyList())
//            awaitClose {
//                Log.d(TAG, "awaitClose 호출 (부서 없음)")
//            }
//            return@callbackFlow
//        }
//
//        val today = LocalDate.now().format(fmt)
//        Log.d(TAG, "오늘 날짜: $today")
//
//        val col = db.collection("results").document(today).collection("entries")
//        Log.d(TAG, "Firestore 경로: results/$today/entries")
//
//        val sub = col.addSnapshotListener { snap, e ->
//            Log.d(TAG, "--- SnapshotListener 트리거 ---")
//
//            if (e != null) {
//                Log.e(TAG, "Firestore 에러 발생", e)
//                close(e)
//                return@addSnapshotListener
//            }
//
//            if (snap == null) {
//                Log.w(TAG, "Snapshot이 null")
//                trySend(emptyList())
//                return@addSnapshotListener
//            }
//
//            if (snap.isEmpty) {
//                Log.d(TAG, "Snapshot이 비어있음 (문서 없음)")
//                trySend(emptyList())
//                return@addSnapshotListener
//            }
//
//            Log.d(TAG, "Snapshot 문서 개수: ${snap.documents.size}")
//            val allResults = snap.documents.mapNotNull { it.toObject(ChecklistResult::class.java) }
//            Log.d(TAG, "변환된 ChecklistResult 개수: ${allResults.size}")
//
//            allResults.forEachIndexed { index, result ->
//                Log.d(TAG, "  [$index] userId=${result.userId}, name=${result.name}")
//            }
//
//            val filteredResults = mutableListOf<ChecklistResult>()
//            val latch = CountDownLatch(allResults.size)
//            Log.d(TAG, "CountDownLatch 생성: count=${allResults.size}")
//
//            allResults.forEachIndexed { index, result ->
//                Log.d(TAG, "직원 정보 조회 시작 [$index]: ${result.userId}")
//
//                db.collection("employees").document(result.userId).get()
//                    .addOnSuccessListener { empDoc ->
//                        Log.d(TAG, "직원 문서 조회 성공 [$index]: ${result.userId}, exists=${empDoc.exists()}")
//
//                        val empDept = empDoc.get("dept") as? List<*> ?: emptyList<String>()
//                        val empDeptList = empDept.filterIsInstance<String>()
//                        Log.d(TAG, "  직원 부서: $empDeptList")
//
//                        val isMatch = isSubDept(myDeptList, empDeptList)
//                        Log.d(TAG, "  부서 매칭 결과: $isMatch (내 부서=$myDeptList vs 직원 부서=$empDeptList)")
//
//                        if (isMatch) {
//                            filteredResults.add(result)
//                            Log.d(TAG, "  ✓ 필터링 통과 - 추가됨 (현재 필터링된 개수: ${filteredResults.size})")
//                        } else {
//                            Log.d(TAG, "  ✗ 필터링 제외")
//                        }
//
//                        latch.countDown()
//                        Log.d(TAG, "  latch.countDown() 호출 (남은 count: ${latch.count})")
//                    }
//                    .addOnFailureListener { error ->
//                        Log.e(TAG, "직원 문서 조회 실패 [$index]: ${result.userId}", error)
//                        latch.countDown()
//                        Log.d(TAG, "  latch.countDown() 호출 (실패 케이스, 남은 count: ${latch.count})")
//                    }
//            }
//
//            GlobalScope.launch(Dispatchers.IO) {
//                Log.d(TAG, "GlobalScope 코루틴 시작 - latch.await() 대기")
//                latch.await()
//                Log.d(TAG, "latch.await() 완료 - 최종 필터링된 결과: ${filteredResults.size}개")
//
//                filteredResults.forEachIndexed { index, result ->
//                    Log.d(TAG, "  최종 결과[$index]: ${result.name} (${result.userId})")
//                }
//
//                val sendResult = trySend(filteredResults)
//                Log.d(TAG, "trySend 결과: ${if (sendResult.isSuccess) "성공" else "실패"}")
//            }
//        }
//
//        awaitClose {
//            Log.d(TAG, "=== awaitClose 호출 - 리스너 제거 ===")
//            sub.remove()
//        }
    }

    /** 내 부서(myDept)가 상대방 부서(empDept)의 앞부분 인지 비교 */
    private fun isSubDept(myDept: List<String>, targetDept: List<String>): Boolean {
        if (myDept.isEmpty() || targetDept.isEmpty()) return false
        if (targetDept.size < myDept.size) return false
        return targetDept.take(myDept.size) == myDept
    }

    /** 특정 날짜의 결과 리스트를 실시간 스트리밍 (부서 필터링 포함) */
    fun watchResultsByDate(date: LocalDate, context: Context): Flow<List<ChecklistResult>> = callbackFlow {
        val TAG = "watchResultsByDate"
        Log.d(TAG, "=== Flow 시작 ===")

        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val myDeptStr = prefs.getString("dept", "") ?: ""
        Log.d(TAG, "내 부서: '$myDeptStr'")

        if (myDeptStr.isEmpty() || myDeptStr == "미등록") {
            Log.d(TAG, "부서 없음 - 빈 리스트 반환")
            trySend(emptyList())
            awaitClose {
                Log.d(TAG, "awaitClose (부서 없음)")
            }
            return@callbackFlow
        }

        val dateString = date.format(fmt)
        Log.d(TAG, "조회 날짜: $dateString")

        val col = db.collection("results")
            .document(dateString)
            .collection("entries")
        Log.d(TAG, "Firestore 경로: results/$dateString/entries")

        val sub = col.addSnapshotListener { snap, e ->
            Log.d(TAG, "--- SnapshotListener 트리거 ---")

            if (e != null) {
                Log.e(TAG, "Firestore 에러", e)
                close(e)
                return@addSnapshotListener
            }

            if (snap == null || snap.isEmpty) {
                Log.d(TAG, "데이터 없음")
                trySend(emptyList())
                return@addSnapshotListener
            }

            Log.d(TAG, "문서 개수: ${snap.documents.size}")

            val allResults = snap.documents.mapNotNull { ds ->
                try {
                    val data = ds.data ?: return@mapNotNull null
                    val deptRaw = data["dept"]
                    val dept = when (deptRaw) {
                        is String -> deptRaw
                        is List<*> -> deptRaw.filterIsInstance<String>().joinToString("/")
                        else -> ""
                    }

                    ChecklistResult(
                        userId = data["userId"] as? String ?: "",
                        name = data["name"] as? String ?: "",
                        dept = dept,
                        date = data["date"] as? String ?: "",
                        checklistScore = (data["checklistScore"] as? Number)?.toInt() ?: 0,
                        finalScore = (data["finalScore"] as? Number)?.toInt() ?: 0,
                        finalSafetyScore = (data["finalSafetyScore"] as? Number)?.toInt() ?: 0,
                        ppgScore = (data["ppgScore"] as? Number)?.toInt() ?: 0,
                        pupilScore = (data["pupilScore"] as? Number)?.toInt() ?: 0,
                        tremorScore = (data["tremorScore"] as? Number)?.toInt() ?: 0,
                        safetyLevel = data["safetyLevel"] as? String ?: "",
                        recommendations = (data["recommendations"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        timestamp = (data["timestamp"] as? Long) ?: 0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "파싱 실패: ${ds.id}", e)
                    null
                }
            }

            val filteredResults = allResults.filter { result ->
                result.dept.startsWith(myDeptStr)
            }

            Log.d(TAG, "필터링 결과: ${filteredResults.size}개")
            trySend(filteredResults)
        }

        awaitClose {
            Log.d(TAG, "리스너 제거")
            sub.remove()
        }
    }

    /** 특정 날짜의 결과 리스트를 한 번만 가져오기 */
    suspend fun getResultsByDate(date: LocalDate): List<ChecklistResult> {
        return try {
            val dateString = date.format(fmt)
            val snapshot = db.collection("results")
                .document(dateString)
                .collection("entries")
                .get()
                .await()

            snapshot.documents.mapNotNull { ds ->
                ds.toObject(ChecklistResult::class.java)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 여러 날짜 범위의 결과를 가져오기 (일주일, 한 달 등) */
    suspend fun getResultsByDateRange(startDate: LocalDate, endDate: LocalDate): List<ChecklistResult> {
        val results = mutableListOf<ChecklistResult>()

        var currentDate = startDate
        while (!currentDate.isAfter(endDate)) {
            val dateResults = getResultsByDate(currentDate)
            results.addAll(dateResults)
            currentDate = currentDate.plusDays(1)
        }

        return results
    }

    /** 지난 7일간의 결과를 가져오기 */
    suspend fun getLastWeekResults(): List<ChecklistResult> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(6)
        return getResultsByDateRange(startDate, endDate)
    }
}