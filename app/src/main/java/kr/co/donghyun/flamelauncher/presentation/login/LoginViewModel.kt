package kr.co.donghyun.flamelauncher.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.domain.repository.AuthRepository
import javax.inject.Inject

sealed interface LoginEvent {
    data object Success : LoginEvent
    data class Failure(val message: String?) : LoginEvent
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun getAuthUrl(): String = repository.getAuthUrl()

    fun isRedirectUri(url: String): Boolean = repository.isRedirectUri(url)

    fun loginOffline(username: String) {
        try {
            repository.loginOffline(username)
            viewModelScope.launch { _events.send(LoginEvent.Success) }
        } catch (e: Exception) {
            _events.trySend(LoginEvent.Failure(e.message))
        }
    }

    fun login(code: String) {
        _isLoading.value = true
        _statusMessage.value = "로그인 중..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.login(code)
                _events.send(LoginEvent.Success)
            } catch (e: Exception) {
                _isLoading.value = false
                _events.send(LoginEvent.Failure(e.message))
            }
        }
    }
}
