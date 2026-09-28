package com.example.cattlemonitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped connectivity tracker: a StateFlow<Boolean> that flips as the
 * device gains/loses usable internet. Screens use it to show an offline
 * state instead of an endless spinner. Callback registration needs
 * ACCESS_NETWORK_STATE (a normal permission) — see AndroidManifest.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                }

                // Per-network event: re-evaluate rather than assume fully offline
                // (another network, e.g. cellular, may still be usable).
                override fun onLost(network: Network) {
                    _isOnline.value = currentlyOnline()
                }

                override fun onUnavailable() {
                    _isOnline.value = false
                }
            },
        )
    }

    private fun currentlyOnline(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
