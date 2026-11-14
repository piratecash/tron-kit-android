package io.horizontalsystems.tronkit

import android.util.Log

internal actual fun logWarning(message: String, error: Throwable) {
    Log.w("e", message, error)
}
