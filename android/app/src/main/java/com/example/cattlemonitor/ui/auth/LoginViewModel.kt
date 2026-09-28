package com.example.cattlemonitor.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cattlemonitor.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {

    private val auth = ServiceLocator.auth

    /** Seconds left before the login button unlocks again (0 = unlocked). */
    private val _lockoutSeconds = MutableStateFlow(0)
    val lockoutSeconds: StateFlow<Int> = _lockoutSeconds

    /** Consecutive failed sign-ins on this device (drives the reset hint). */
    private val _failedAttempts = MutableStateFlow(0)
    val failedAttempts: StateFlow<Int> = _failedAttempts

    init {
        // Restore a pending lockout left over from a previous session/process.
        viewModelScope.launch {
            val ms = auth.lockoutRemainingMs()
            if (ms > 0) startCooldown((ms / 1000).toInt())
            _failedAttempts.value = auth.failedAttempts()
        }
    }

    fun signIn(email: String, password: String, onDone: (Result<Unit>) -> Unit) {
        if (_lockoutSeconds.value > 0) return
        viewModelScope.launch {
            val result = auth.signIn(email, password)
            result.fold(
                onSuccess = { onDone(result) },
                onFailure = { failure ->
                    val state = auth.registerFailure()
                    _failedAttempts.value = state.failedCount
                    if (state.lockedUntilEpochMs > System.currentTimeMillis()) {
                        startCooldown(com.example.cattlemonitor.data.LOCKOUT_MS.toInt() / 1000)
                    }
                    onDone(Result.failure(failure))
                },
            )
        }
    }

    fun signUp(email: String, password: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onDone(auth.signUp(email, password)) }
    }

    fun sendPasswordReset(email: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onDone(auth.sendPasswordReset(email)) }
    }

    fun clearFailures() {
        viewModelScope.launch { auth.clearFailures() }
    }

    private fun startCooldown(seconds: Int) {
        _lockoutSeconds.value = seconds
        viewModelScope.launch {
            while (_lockoutSeconds.value > 0) {
                delay(1_000)
                _lockoutSeconds.value -= 1
            }
        }
    }
}
