package com.example.cattlemonitor.ui.overview

import com.example.cattlemonitor.ui.common.RefreshableViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Cow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class OverviewViewModel : RefreshableViewModel() {
    private val repo = ServiceLocator.repository

    val cows: StateFlow<List<Cow>?> = repo.observeCows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
