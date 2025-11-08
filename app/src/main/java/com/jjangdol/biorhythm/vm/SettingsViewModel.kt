package com.jjangdol.biorhythm.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.model.ChecklistConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val db = FirebaseFirestore.getInstance()
    private val _items = MutableStateFlow<List<ChecklistConfig>>(emptyList())
    val items: StateFlow<List<ChecklistConfig>> = _items.asStateFlow()

    init {
        loadItems()
    }

    private fun loadItems() {
        viewModelScope.launch {
            try {
                val snapshot = db.collection("checklist")
                    .orderBy("order")
                    .get()
                    .await()

                val itemsList = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ChecklistConfig::class.java)?.copy(id = doc.id)
                }

                _items.value = itemsList
            } catch (e: Exception) {
                // 에러 처리
            }
        }
    }

    fun addQuestion(
        question: String,
        weight: Int,
        options: List<String>,
        optionWeights: List<Int>
    ) {
        val newItem = ChecklistConfig(
            id = System.currentTimeMillis().toString(),
            question = question,
            weight = weight,
            options = options,
            optionWeights = optionWeights
        )

        val updatedList = _items.value.toMutableList().apply {
            add(newItem)
        }
        _items.value = updatedList

        // Firestore 저장
        viewModelScope.launch {
            try {
                db.collection("checklist")
                    .document(newItem.id)
                    .set(newItem)
                    .addOnSuccessListener {
                        Log.d("Firestore", "문항 저장 성공: ${newItem.id}")
                    }
                    .addOnFailureListener { e ->
                        Log.e("Firestore", "문항 저장 실패", e)
                    }
            } catch (e: Exception) {
                Log.e("Firestore", "문항 저장 중 예외", e)
            }
        }
    }

    fun updateWeight(position: Int, weight: Int) {
        viewModelScope.launch {
            try {
                val items = _items.value.toMutableList()
                if (position in items.indices) {
                    items[position] = items[position].copy(weight = weight)
                    _items.value = items

                    // Firestore 업데이트
                    updateItemInFirestore(items[position])
                }
            } catch (e: Exception) {
                // 에러 처리
            }
        }
    }

    fun updateQuestion(position: Int, newQuestion: String) {
        viewModelScope.launch {
            try {
                val items = _items.value.toMutableList()
                if (position in items.indices) {
                    items[position] = items[position].copy(question = newQuestion)
                    _items.value = items

                    // Firestore 업데이트
                    updateItemInFirestore(items[position])
                }
            } catch (e: Exception) {
                // 에러 처리
            }
        }
    }

    fun removeQuestion(position: Int) {
        viewModelScope.launch {
            try {
                val items = _items.value.toMutableList()
                if (position in items.indices) {
                    val itemToRemove = items[position]
                    items.removeAt(position)

                    // 순서 재정렬addQuestion
                    items.forEachIndexed { index, item ->
                        items[index] = item.copy(order = index)
                    }

                    _items.value = items

                    // Firestore에서 삭제
                    deleteItemFromFirestore(itemToRemove)

                    // 순서 업데이트
                    updateAllOrdersInFirestore(items)
                }
            } catch (e: Exception) {
                // 에러 처리
            }
        }
    }

    private suspend fun updateItemInFirestore(item: ChecklistConfig) {
        try {
            if (item.id.isNotEmpty()) {
                db.collection("checklist")
                    .document(item.id)
                    .set(item)
                    .await()
            }
        } catch (e: Exception) {
            // 에러 처리
        }
    }

    private suspend fun deleteItemFromFirestore(item: ChecklistConfig) {
        try {
            if (item.id.isNotEmpty()) {
                db.collection("checklist")
                    .document(item.id)
                    .delete()
                    .await()
            }
        } catch (e: Exception) {
            // 에러 처리
        }
    }

    private suspend fun updateAllOrdersInFirestore(items: List<ChecklistConfig>) {
        try {
            items.forEach { item ->
                updateItemInFirestore(item)
            }
        } catch (e: Exception) {
            // 에러 처리
        }
    }
}