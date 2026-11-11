package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.banshan.BanShanLoginStage
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.main.MainActivity

/**
 * BanShan account screen: first-run login and the account re-login entry from the drawer.
 */
class LoginActivity : BaseComponentActivity() {

    private val loginViewModel: LoginViewModel by viewModels()

    private val relogin: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_RELOGIN, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        val state by loginViewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            loginViewModel.events.collect { event ->
                if (event is LoginViewModel.LoginEvent.Succeeded) onLoginSucceeded()
            }
        }

        LoginScreen(
            viewModel = loginViewModel,
            state = state,
            relogin = relogin,
            onBackClick = ::onBackClick,
            onLoginClick = loginViewModel::submit,
        )
    }

    private fun onLoginSucceeded() {
        toast(R.string.banshan_login_success)
        if (relogin) {
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun onBackClick() {
        // First-run login is mandatory; there is no main screen to return to.
        if (relogin) {
            finish()
        } else {
            finishAffinity()
        }
    }

    companion object {
        const val EXTRA_RELOGIN = "banshan_extra_relogin"
    }
}

@Composable
private fun LoginScreen(
    viewModel: LoginViewModel,
    state: LoginUiState,
    relogin: Boolean,
    onBackClick: () -> Unit,
    onLoginClick: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (relogin) R.string.banshan_account_title else R.string.banshan_login_title
                ),
                onBackClick = onBackClick,
                isLoading = state.isLoggingIn,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.banshan_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (relogin && !state.currentUsername.isNullOrEmpty()) {
                Text(
                    text = stringResource(
                        R.string.banshan_login_current_account,
                        state.currentUsername
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(R.string.banshan_login_username)) },
                singleLine = true,
                enabled = !state.isLoggingIn,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.banshan_login_password)) },
                singleLine = true,
                enabled = !state.isLoggingIn,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onLoginClick,
                enabled = !state.isLoggingIn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.banshan_login_action))
            }

            if (state.isLoggingIn) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(
                        when (state.stage) {
                            BanShanLoginStage.LOADING_NODES -> R.string.banshan_login_loading_nodes
                            else -> R.string.banshan_login_signing_in
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val errorText = state.errorMessage
                ?: state.errorRes?.let { stringResource(it) }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            NavigationBarsSpacer()
        }
    }
}
