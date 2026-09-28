package io.horizontalsystems.tronkit.network

import io.horizontalsystems.tronkit.PlatformContext

expect class ConnectionManager(context: PlatformContext) {

    interface Listener {
        fun onConnectionChange()
    }

    var listener: Listener?
    var isConnected: Boolean

    fun stop()
}
