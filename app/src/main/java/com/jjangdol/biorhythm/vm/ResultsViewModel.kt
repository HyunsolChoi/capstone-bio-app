// app/src/main/java/com/jjangdol/biorhythm/vm/ResultsViewModel.kt
package com.jjangdol.biorhythm.vm

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.model.ChecklistResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository,
    private val application: Application
) : AndroidViewModel(application) {

    // UI에서 관찰할 dailyResults Flow
    private val _dailyResults = MutableStateFlow<List<ChecklistResult>>(emptyList())
    val dailyResults: StateFlow<List<ChecklistResult>> = _dailyResults

    init {
        fetchDailyResults()
    }

    private fun getUserId(): String? {
        val prefs = application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        //val dept = prefs.getString("user_dept", "") ?: ""
        val name = prefs.getString("user_name", "") ?: ""
        //val dob = prefs.getString("dob", "") ?: ""
        val empNum = prefs.getString("user_empNum", "") ?: ""

        return if (name.isNotEmpty() && empNum.isNotEmpty()) {
            userRepository.getUserId(name, empNum)
        } else {
            null
        }
    }

    /** Firestore 에서 "results/{userId}/daily" 문서들을 실시간으로 가져와서 _dailyResults 에 업데이트 */
    fun fetchDailyResults() {
        val userId = getUserId()
        if (userId.isNullOrEmpty()) {
            Log.w(TAG, "fetchDailyResults: user not found")
            return
        }
        firestore
            .collection("results")
            .document(userId)
            .collection("daily")
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    Log.e(TAG, "Error listening for daily results", err)
                    return@addSnapshotListener
                }
                val list = snaps
                    ?.documents
                    ?.mapNotNull { doc ->
                        // ChecklistResult 데이터 클래스로 바로 변환
                        doc.toObject(ChecklistResult::class.java)
                    }
                    ?: emptyList()

                viewModelScope.launch {
                    _dailyResults.value = list
                }
            }
    }

    companion object {
        private const val TAG = "ResultsViewModel"
    }
}