package com.example.cattlemonitor.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.Reading
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Date

class DetailViewModel(cowId: String) : ViewModel() {
    private val repo = ServiceLocator.repository

    val cow: StateFlow<Cow?> = repo.observeCow(cowId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val readings: StateFlow<List<Reading>?> = repo.observeReadings(cowId, Date(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val alerts: StateFlow<List<Alert>?> = repo.observeAlerts(cowId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
