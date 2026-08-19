package ir.vinor.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

enum class VinorNetState {
    ONLINE,
    OFFLINE,
    LIMITED,
}

object VinorNetworkStatus {

    fun current(context: Context): VinorNetState {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return VinorNetState.OFFLINE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return VinorNetState.OFFLINE
            val caps = cm.getNetworkCapabilities(nw) ?: return VinorNetState.OFFLINE
            when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                    VinorNetState.ONLINE
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                    VinorNetState.LIMITED
                else -> VinorNetState.OFFLINE
            }
        } else {
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (ni != null && ni.isConnected) VinorNetState.ONLINE else VinorNetState.OFFLINE
        }
    }

    fun isOnline(context: Context): Boolean = current(context) == VinorNetState.ONLINE

    fun useCacheFirst(context: Context): Boolean = current(context) != VinorNetState.ONLINE
}
