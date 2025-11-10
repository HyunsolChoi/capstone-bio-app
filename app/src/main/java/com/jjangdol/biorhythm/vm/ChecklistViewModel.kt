package com.jjangdol.biorhythm.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.model.ChecklistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChecklistViewModel @Inject constructor(
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _items = MutableStateFlow<List<ChecklistItem>>(emptyList())
    val items: StateFlow<List<ChecklistItem>> = _items

    init {
        val db = firestore
            .collection("checklist")

        db.addSnapshotListener { snaps, err ->
            if (err != null) {
                err.printStackTrace()
                return@addSnapshotListener
            }
            val list = snaps
                ?.documents
                ?.mapNotNull { doc ->
                    val question = doc.getString("question") ?: return@mapNotNull null
                    val weight = doc.getLong("weight")?.toInt() ?: return@mapNotNull null
                    val options = doc.get("options") as? List<String>
                    val optionWeights = (doc.get("optionWeights") as? List<*>)?.mapNotNull {
                        (it as? Number)?.toInt()
                    }
                    ChecklistItem(
                        id = doc.id,
                        question = question,
                        _weight = weight,
                        _optionWeights = optionWeights,
                        options = options
                    )
                }
                ?: emptyList()


            viewModelScope.launch {
                _items.value = list
            }
        }
    }

    /**
     * 사용자가 답을 변경했을 때 호출
     */
    fun answerChanged(position: Int, selectedOption: Int) {
        val updatedList = items.value.toMutableList()
        val item = updatedList[position]
        updatedList[position] = item.copy(_selectedOption = selectedOption)
        _items.value = updatedList
    }
}

