package com.jjangdol.biorhythm.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjangdol.biorhythm.data.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    /** 로그인 버튼 클릭 시 호출 */
    fun login(name: String, Enum: String) {
        if (name.isBlank() || Enum.isBlank()) {
            _state.value = LoginState.Error("모든 항목을 입력해주세요.")
            return
        }

        viewModelScope.launch {
            _state.value = LoginState.Loading
            try {
                repo.signInAndSaveProfile(name, Enum)
                _state.value = LoginState.Success
            } catch (e: Exception) {
                _state.value = LoginState.Error(e.message ?: "로그인에 실패했습니다.")
            }
        }
    }
}
