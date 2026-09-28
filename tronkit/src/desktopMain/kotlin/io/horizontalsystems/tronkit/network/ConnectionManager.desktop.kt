package io.horizontalsystems.tronkit.network

import io.horizontalsystems.tronkit.PlatformContext

// Desktop has no connectivity service to subscribe to, so the kit always syncs;
// SyncTimer evaluates isConnected itself, so the listener is never called.
actual class ConnectionManager actual constructor(context: PlatformContext) {

    actual interface Listener {
        actual fun onConnectionChange()
    }

    actual var listener: Listener? = null
    actual var isConnected: Boolean = true

    actual fun stop() = Unit
}
