package com.example.cattlemonitor.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.Cow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AlertsViewModel : ViewModel() {
    val alerts: StateFlow<List<Alert>?> = ServiceLocator.repository.observeAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Used to show each alert's cow name and photo instead of a bare ID. */
    val cows: StateFlow<List<Cow>?> = ServiceLocator.repository.observeCows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
