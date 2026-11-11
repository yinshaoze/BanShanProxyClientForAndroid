package com.v2ray.ang.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.banshan.BanShanLoginFailure
import com.v2ray.ang.banshan.BanShanLoginResult
import com.v2ray.ang.banshan.BanShanLoginStage
import com.v2ray.ang.banshan.BanShanSession
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val currentUsername: String? = null,
    val isLoggingIn: Boolean = false,
    val stage: BanShanLoginStage? = null,
    @StringRes val errorRes: Int? = null,
    val errorMessage: String? = null,
)

class LoginViewModel(application: Application) : BaseViewModel(application) {

    sealed interface LoginEvent : ViewModelEvent {
        data object Succeeded : LoginEvent
    }

    private val _uiState = MutableStateFlow(
        LoginUiState(currentUsername = BanShanSession.username().takeIf { it.isNotEmpty() })
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorRes = null, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorRes = null, errorMessage = null) }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLoggingIn) return
        when {
            state.username.isBlank() -> {
                _uiState.update { it.copy(errorRes = R.string.banshan_login_empty_username) }
                return
            }

            state.password.isEmpty() -> {
                _uiState.update { it.copy(errorRes = R.string.banshan_login_empty_password) }
                return
            }
        }

        _uiState.update {
            it.copy(
                isLoggingIn = true,
                stage = null,
                errorRes = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            val result = BanShanSession.signIn(state.username, state.password) { stage ->
                _uiState.update { it.copy(stage = stage) }
            }
            when (result) {
                is BanShanLoginResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoggingIn = false,
                            stage = null,
                            currentUsername = result.username,
                        )
                    }
                    _events.send(LoginEvent.Succeeded)
                }

                is BanShanLoginResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoggingIn = false,
                            stage = null,
                            errorRes = failureRes(result.reason),
                            errorMessage = result.serverMessage,
                        )
                    }
                }
            }
        }
    }

    @StringRes
    private fun failureRes(reason: BanShanLoginFailure): Int = when (reason) {
        BanShanLoginFailure.EMPTY_USERNAME -> R.string.banshan_login_empty_username
        BanShanLoginFailure.EMPTY_PASSWORD -> R.string.banshan_login_empty_password
        BanShanLoginFailure.NETWORK -> R.string.banshan_login_network_error
        BanShanLoginFailure.BAD_RESPONSE -> R.string.banshan_login_bad_response
        BanShanLoginFailure.SERVER_REJECTED -> R.string.banshan_login_rejected
        BanShanLoginFailure.NO_NODES -> R.string.banshan_login_no_nodes
    }
}
