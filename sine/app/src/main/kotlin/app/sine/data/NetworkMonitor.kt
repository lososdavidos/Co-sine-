package app.sine.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [metered] follows Android's metered flag, not "is it WiFi": a hotspot counts as expensive (§2.5). */
data class NetworkState(val connected: Boolean, val metered: Boolean)

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(current())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = from(caps)
            }

            override fun onLost(network: Network) {
                _state.value = NetworkState(connected = false, metered = false)
            }
        })
    }

    private fun current(): NetworkState =
        cm.getNetworkCapabilities(cm.activeNetwork)?.let(::from) ?: NetworkState(false, false)

    private fun from(caps: NetworkCapabilities) = NetworkState(
        connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
