package com.example.cattlemonitor.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.data.MAX_LOGIN_ATTEMPTS
import com.example.cattlemonitor.ui.settings.LanguagePickerButton
import com.example.cattlemonitor.ui.theme.Brand

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, vm: LoginViewModel = viewModel()) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    val lockoutSeconds by vm.lockoutSeconds.collectAsState()
    val failedAttempts by vm.failedAttempts.collectAsState()
    val locked = lockoutSeconds > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // Language switch must be reachable BEFORE signing in — someone facing
        // an Indonesian-only phone shouldn't need to guess at English UI to
        // find the settings. Own row, top-right, clear of the logo.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguagePickerButton()
        }
        Image(
            painter = painterResource(R.drawable.decow_logo),
            contentDescription = "DeCow",
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(
                if (mode == AuthMode.SIGN_IN) R.string.login_welcome else R.string.login_create_account,
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text(stringResource(R.string.field_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text(stringResource(R.string.field_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        info?.let {
            Text(
                it,
                color = Brand,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                loading = true
                error = null
                info = null
                val onDone: (Result<Unit>) -> Unit = { result ->
                    loading = false
                    result.fold(
                        onSuccess = { onLoggedIn() },
                        onFailure = { error = mapAuthError(context, it) },
                    )
                }
                if (mode == AuthMode.SIGN_IN) vm.signIn(email.trim(), password, onDone)
                else vm.signUp(email.trim(), password, onDone)
            },
            enabled = !loading && !locked && email.isNotBlank() && password.length >= 6,
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Text(
                when {
                    loading -> "…"
                    locked -> stringResource(R.string.login_locked_button, lockoutSeconds)
                    mode == AuthMode.SIGN_IN -> stringResource(R.string.login_button)
                    else -> stringResource(R.string.signup_button)
                },
            )
        }
        if (locked) {
            Text(
                stringResource(R.string.login_locked_message, MAX_LOGIN_ATTEMPTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (mode == AuthMode.SIGN_IN && failedAttempts >= MAX_LOGIN_ATTEMPTS - 2) {
            // Getting close to the limit — nudge toward the reset flow.
            Text(
                stringResource(R.string.login_attempts_hint, failedAttempts, MAX_LOGIN_ATTEMPTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (mode == AuthMode.SIGN_IN) {
            TextButton(
                onClick = { showResetDialog = true; error = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.forgot_password), color = Brand, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(
            onClick = { mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN; error = null; info = null },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (mode == AuthMode.SIGN_IN) R.string.login_switch_to_signup else R.string.signup_switch_to_login,
                ),
                color = Brand,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showResetDialog) {
        var resetEmail by remember { mutableStateOf(email) }
        var resetBusy by remember { mutableStateOf(false) }
        var resetError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!resetBusy) showResetDialog = false },
            title = { Text(stringResource(R.string.reset_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.reset_password_body))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it; resetError = null },
                        label = { Text(stringResource(R.string.field_email)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    resetError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        resetBusy = true
                        resetError = null
                        vm.sendPasswordReset(resetEmail.trim()) { result ->
                            resetBusy = false
                            result.fold(
                                onSuccess = {
                                    showResetDialog = false
                                    info = context.getString(R.string.reset_password_sent)
                                },
                                onFailure = { resetError = mapAuthError(context, it) },
                            )
                        }
                    },
                    enabled = !resetBusy && resetEmail.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand),
                ) { Text(if (resetBusy) "…" else stringResource(R.string.reset_password_send)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private enum class AuthMode { SIGN_IN, SIGN_UP }

/** Raw GoTrue/ktor error text → a message in the user's language. */
private fun mapAuthError(context: android.content.Context, t: Throwable): String {
    val msg = (t.message ?: "").lowercase()
    return when {
        "invalid login credentials" in msg -> context.getString(R.string.error_invalid_credentials)
        "email not confirmed" in msg -> context.getString(R.string.error_email_not_confirmed)
        "over_email_send_rate_limit" in msg || "rate limit" in msg -> context.getString(R.string.error_rate_limited)
        "already registered" in msg || "already been registered" in msg -> context.getString(R.string.error_email_in_use)
        "password should be at least" in msg || "weak password" in msg -> context.getString(R.string.error_weak_password)
        "valid email" in msg || "invalid format" in msg -> context.getString(R.string.error_invalid_email)
        "unable to resolve host" in msg || "failed to connect" in msg || t is java.io.IOException ->
            context.getString(R.string.error_network)
        else -> t.message ?: context.getString(R.string.error_generic)
    }
}
