package com.example.cattlemonitor.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Cow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceViewModel : ViewModel() {
    private val repo = ServiceLocator.repository

    val cows: StateFlow<List<Cow>?> = repo.observeCows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun addCow(name: String, deviceId: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch { onDone(repo.addCow(name, deviceId)) }
    }

    fun updateCow(id: String, name: String?, deviceId: String?, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onDone(repo.updateCow(id, name, deviceId)) }
    }

    fun deleteCow(id: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onDone(repo.deleteCow(id)) }
    }
}
